package net.pocketnai.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 画廊时间轴上的一个日期分组。
 *
 * [epochDay] 是本地时区下的自然日编号：同一天必然同组，也是列表 key 的来源
 * （key 不能用 [label] —— "今天"明天就变成"昨天"了）。
 */
data class GalleryDaySection(
    val epochDay: Long,
    /** 分组标题："今天" / "昨天" / "9月14日" / "2025年12月31日"。 */
    val label: String,
    val items: List<GalleryItem>,
)

/**
 * 把画廊切成"按生成日期分组"的时间轴，像系统相册那样。
 *
 * 找一张图时用户先想起的是"前两天生成的那张"，而不是"第 137 张"，所以按天分组
 * 是相册的天然边界。分组用**本地时区**的自然日：跨时区旅行导致的分组差异是
 * 任何相册都有的行为，不为此做特殊处理。
 */
object GalleryTimeline {

    private val SAME_YEAR_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M月d日", Locale.CHINESE)

    private val OTHER_YEAR_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINESE)

    /**
     * 把画廊列表切成按日期分组的时间轴。
     *
     * 输出按日期**降序**（最新的一天在最上面），与画廊查询的 `createdAt DESC` 一致；
     * 输入顺序不参与结果的排列 —— 顺序是这条纯函数的契约的一部分，
     * 不指望调用方永远记得先把列表排好序。
     */
    fun group(items: List<GalleryItem>, now: Long, zone: ZoneId): List<GalleryDaySection> {
        if (items.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return items
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .map { (date, dayItems) ->
                GalleryDaySection(
                    epochDay = date.toEpochDay(),
                    label = labelFor(date, today),
                    items = dayItems,
                )
            }
            .sortedByDescending { it.epochDay }
    }

    /**
     * "今天 / 昨天 / 同年省略年份 / 跨年带年份"。
     *
     * "昨天"优先于"同年省略年份"：1 月 1 日回看 12 月 31 日时，
     * 用户想看到的是"昨天"而不是"2025年12月31日"。
     */
    fun labelFor(date: LocalDate, today: LocalDate): String = when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> SAME_YEAR_FORMAT.format(date)
        else -> OTHER_YEAR_FORMAT.format(date)
    }
}
