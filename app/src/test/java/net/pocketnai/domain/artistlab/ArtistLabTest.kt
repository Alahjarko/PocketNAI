package net.pocketnai.domain.artistlab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.SerialGenerationGate
import org.junit.Test
import kotlin.random.Random

class ArtistLabTest {
    @Test fun `导入去重与移出状态往返后的一百次调用仍严格串行`() = runTest {
        val existing = ArtistCatalogEntry("artist: anmi", "Anmi")
        val text = ArtistCatalogImport.parse("\uFEFFartist: ANMI\r\nartist: salmon88\nartist: new_artist\nartist: salmon88\n")
        val incoming = ArtistCatalogImport.parse(ArtistCatalogExport.configuration(text.artists,
            setOf("artist: new artist")))
        val merged = ArtistCatalogImport.merge(listOf(existing), setOf(existing.tag), incoming)
        assertThat(merged.artists.map { it.tag }).containsExactly("artist: anmi", "artist: salmon88", "artist: new artist").inOrder()
        assertThat(merged.excluded).containsExactly("artist: anmi", "artist: new artist")
        val pool = merged.artists.filterNot { it.tag in merged.excluded }.map { it.tag }
        val planned = ArtistLabPlanner.plan(pool, ArtistLabConfig("", 1, 20, 20, 100, "imported"), Random(42))
        assertThat(planned.all { it.prompt == "1.00::artist: salmon88, ::" }).isTrue()
        assertThat(runCatching { ArtistCatalogImport.parse("artist: salmon88, masterpiece") }.isFailure).isTrue()
        assertThat(runCatching { ArtistCatalogImport.parse("""{"schemaVersion":2,"artists":[{"tag":"artist: salmon88","profileUrl":"file:///private/account"}]}""") }.isFailure).isTrue()
        var active = 0
        var maxActive = 0
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<Int>()
        ArtistLabQueue(wait = { waits += it }, interval = { if (calls.size % 2 == 0) 1000 else 2000 })
            .run(planned.indices.toList(), { false }) { active++; maxActive = maxOf(maxActive, active); calls += it + 1; active-- }
        assertThat(calls).containsExactlyElementsIn(1..100).inOrder()
        assertThat(waits).hasSize(100)
        assertThat(waits.all { it in 1000..2000 }).isTrue()
        assertThat(maxActive).isEqualTo(1)

        // Home submissions arrive concurrently but must retain FIFO and a post-completion gap.
        val gate = SerialGenerationGate(clockMillis = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        val order = mutableListOf<Int>()
        coroutineScope {
            (1..3).map { ordinal -> async {
                gate.run {
                    active++; maxActive = maxOf(maxActive, active)
                    starts += testScheduler.currentTime; order += ordinal
                    delay(125)
                    active--
                }
            } }.awaitAll()
        }
        assertThat(order).containsExactly(1, 2, 3).inOrder()
        assertThat(starts.zipWithNext { a, b -> b - a }).containsExactly(1125L, 1125L)
        assertThat(maxActive).isEqualTo(1)
        val approval = gate.epoch
        var unsent = 0
        coroutineScope {
            val failing = async { runCatching { gate.run(approval) { gate.stopPending(); error("fake failure") } } }
            val pending = async { runCatching { gate.run(approval) { unsent++ } } }
            assertThat(failing.await().isFailure).isTrue()
            assertThat(pending.await().exceptionOrNull()).isInstanceOf(SerialGenerationGate.Stopped::class.java)
        }
        assertThat(unsent).isEqualTo(0)
        gate.run { unsent++ } // A new explicit submission, not a retry of the interrupted one.
        assertThat(unsent).isEqualTo(1)

        // Locked base artists: included first without duplicates, remainder drawn randomly from pool - locked.
        val multiPool = listOf(
            "artist: alpha", "artist: beta", "artist: gamma",
            "artist: delta", "artist: epsilon", "artist: zeta",
        )
        val locked = listOf("artist: alpha", "artist: beta")
        val lockedMixes = ArtistLabPlanner.plan(
            pool = multiPool,
            config = ArtistLabConfig(
                paramsJson = "",
                artistCount = 4,
                minTicks = 10,
                maxTicks = 30,
                drawCount = 40,
                catalogHash = "locked-test",
                lockedArtists = locked,
            ),
            random = Random(7),
        )
        assertThat(lockedMixes).hasSize(40)
        lockedMixes.forEach { mix ->
            val tags = mix.artists.map { it.tag }
            assertThat(tags).hasSize(4)
            assertThat(tags.distinct()).hasSize(4)
            assertThat(tags.take(2)).containsExactly("artist: alpha", "artist: beta").inOrder()
            assertThat(tags.drop(2).all { it in multiPool && it !in locked }).isTrue()
            assertThat(mix.artists.all { it.ticks in 10..30 }).isTrue()
        }
        // Remainder slots vary across draws rather than always taking the same artists.
        assertThat(lockedMixes.map { it.artists.drop(2).map(ArtistTag::tag) }.distinct().size).isGreaterThan(1)

        // When locked count exceeds artistCount, only the first min(3, artistCount) locked artists are used cleanly.
        val cappedLocked = ArtistLabPlanner.plan(
            pool = multiPool,
            config = ArtistLabConfig(
                paramsJson = "",
                artistCount = 2,
                minTicks = 14,
                maxTicks = 26,
                drawCount = 10,
                catalogHash = "capped-test",
                lockedArtists = listOf("artist: alpha", "artist: beta", "artist: gamma"),
            ),
            random = Random(11),
        )
        cappedLocked.forEach { mix ->
            assertThat(mix.artists.map { it.tag }).containsExactly("artist: alpha", "artist: beta").inOrder()
        }

        // Tiered weights (primary upper half, supporting lower half) within minTicks..maxTicks.
        val (lowerMaxEven, upperMinEven) = ArtistLabPlanner.tieredBounds(10, 30)
        assertThat(lowerMaxEven).isEqualTo(20)
        assertThat(upperMinEven).isEqualTo(20)
        val tieredWithLocked = ArtistLabPlanner.plan(
            pool = multiPool,
            config = ArtistLabConfig(
                paramsJson = "",
                artistCount = 4,
                minTicks = 10,
                maxTicks = 30,
                drawCount = 40,
                catalogHash = "tiered-locked",
                lockedArtists = locked,
                tieredWeights = true,
            ),
            random = Random(19),
        )
        tieredWithLocked.forEach { mix ->
            val primary = mix.artists.take(2)
            val supporting = mix.artists.drop(2)
            assertThat(primary.all { it.ticks in upperMinEven..30 }).isTrue()
            assertThat(supporting.all { it.ticks in 10..lowerMaxEven }).isTrue()
        }

        val (lowerMaxOdd, upperMinOdd) = ArtistLabPlanner.tieredBounds(11, 30)
        assertThat(lowerMaxOdd).isEqualTo(20)
        assertThat(upperMinOdd).isEqualTo(21)
        val tieredUnlocked = ArtistLabPlanner.plan(
            pool = multiPool,
            config = ArtistLabConfig(
                paramsJson = "",
                artistCount = 3,
                minTicks = 11,
                maxTicks = 30,
                drawCount = 40,
                catalogHash = "tiered-unlocked",
                tieredWeights = true,
            ),
            random = Random(23),
        )
        tieredUnlocked.forEach { mix ->
            assertThat(mix.artists.first().ticks in upperMinOdd..30).isTrue()
            assertThat(mix.artists.drop(1).all { it.ticks in 11..lowerMaxOdd }).isTrue()
            assertThat(mix.artists.all { it.ticks in 11..30 }).isTrue()
        }

        // Legacy JSON without lockedArtists/tieredWeights still decodes cleanly with safe defaults.
        val compatJson = Json { ignoreUnknownKeys = true }
        val legacyConfig = compatJson.decodeFromString<ArtistLabConfig>(
            """{"paramsJson":"{}","artistCount":3,"minTicks":10,"maxTicks":30,"drawCount":50,"catalogHash":"legacy"}"""
        )
        assertThat(legacyConfig.lockedArtists).isEmpty()
        assertThat(legacyConfig.tieredWeights).isFalse()
        val legacyForm = compatJson.decodeFromString<ArtistLabForm>(
            """{"prompt":"1girl","negative":"lowres","seed":"1","count":"10","artists":3,"minTicks":10,"maxTicks":30}"""
        )
        assertThat(legacyForm.lockedArtists).isEmpty()
        assertThat(legacyForm.tieredWeights).isFalse()
    }
}
