package net.pocketnai.domain.artistlab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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
    }
}
