package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GalleryTimelineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun item(id: String, createdAt: Long) = GalleryItem(
        imageId = id,
        generationId = "generation-$id",
        ordinal = 1,
        relativePath = "generations/generation-$id/0001.png",
        width = 832,
        height = 1216,
        byteSize = 2048L,
        seed = 42L,
        exported = false,
        createdAt = createdAt,
        status = GenerationStatus.SUCCEEDED,
        mode = GenerationMode.TXT2IMG,
        title = "1girl",
        model = ImageModel.V4_5_CURATED,
        prompt = "1girl",
        negativePrompt = "",
        sampleCount = 1,
    )

    /** 以 2026-09-16 12:00（本地时区）为"现在"。 */
    private val now: Long =
        LocalDate.of(2026, 9, 16).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `空列表不产生任何分组`() {
        assertThat(GalleryTimeline.group(emptyList(), now, zone)).isEmpty()
    }

    @Test
    fun `同一天的图片归入同一组`() {
        val sections = GalleryTimeline.group(
            listOf(item("a", at(2026, 9, 16, 9)), item("b", at(2026, 9, 16, 20))),
            now,
            zone,
        )
        assertThat(sections).hasSize(1)
        assertThat(sections[0].items.map { it.imageId }).containsExactly("a", "b")
    }

    @Test
    fun `今天与昨天用相对标签`() {
        val sections = GalleryTimeline.group(
            listOf(item("a", at(2026, 9, 16)), item("b", at(2026, 9, 15))),
            now,
            zone,
        )
        assertThat(sections.map { it.label }).containsExactly("今天", "昨天").inOrder()
    }

    @Test
    fun `同年更早的日期显示月日`() {
        val sections = GalleryTimeline.group(listOf(item("a", at(2026, 9, 14))), now, zone)
        assertThat(sections[0].label).isEqualTo("9月14日")
    }

    @Test
    fun `跨年的日期带年份`() {
        val sections = GalleryTimeline.group(listOf(item("a", at(2025, 12, 31))), now, zone)
        assertThat(sections[0].label).isEqualTo("2025年12月31日")
    }

    @Test
    fun `跨年边界优先显示昨天`() {
        // 1 月 1 日回看 12 月 31 日：用户想看到的是"昨天"，不是"2025年12月31日"。
        val newYear: Long =
            LocalDate.of(2026, 1, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val sections = GalleryTimeline.group(listOf(item("a", at(2025, 12, 31))), newYear, zone)
        assertThat(sections[0].label).isEqualTo("昨天")
    }

    @Test
    fun `分组按日期降序排列且不受输入顺序影响`() {
        val sections = GalleryTimeline.group(
            listOf(
                item("old", at(2026, 9, 14)),
                item("new", at(2026, 9, 16)),
                item("mid", at(2026, 9, 15)),
            ),
            now,
            zone,
        )
        assertThat(sections.map { it.items[0].imageId })
            .containsExactly("new", "mid", "old")
            .inOrder()
    }

    @Test
    fun `组内保持输入顺序`() {
        // DAO 已经按 createdAt DESC 排好；分组不该打乱组内的先后。
        val sections = GalleryTimeline.group(
            listOf(item("late", at(2026, 9, 16, 20)), item("early", at(2026, 9, 16, 8))),
            now,
            zone,
        )
        assertThat(sections[0].items.map { it.imageId })
            .containsExactly("late", "early")
            .inOrder()
    }

    @Test
    fun `epochDay 与标签一起给出且同一天唯一`() {
        val sections = GalleryTimeline.group(
            listOf(item("a", at(2026, 9, 14)), item("b", at(2026, 9, 14, 23))),
            now,
            zone,
        )
        assertThat(sections).hasSize(1)
        assertThat(sections[0].epochDay).isEqualTo(LocalDate.of(2026, 9, 14).toEpochDay())
        assertThat(sections[0].label).isEqualTo("9月14日")
    }
}
