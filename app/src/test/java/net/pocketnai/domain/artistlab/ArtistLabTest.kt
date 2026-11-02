package net.pocketnai.domain.artistlab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    }
}
