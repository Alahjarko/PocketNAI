package net.pocketnai.domain.artistlab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ArtistLabTest {
    @Test fun `一百次独立串行调用严格间隔一至两秒`() = runTest {
        var active = 0
        var maxActive = 0
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<Int>()
        ArtistLabQueue(wait = { waits += it }, interval = { if (calls.size % 2 == 0) 1000 else 2000 })
            .run((1..100).toList(), { false }) { active++; maxActive = maxOf(maxActive, active); calls += it; active-- }
        assertThat(calls).containsExactlyElementsIn(1..100).inOrder()
        assertThat(waits).hasSize(100)
        assertThat(waits.all { it in 1000..2000 }).isTrue()
        assertThat(maxActive).isEqualTo(1)
    }
}
