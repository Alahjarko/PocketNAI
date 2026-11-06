package net.pocketnai.domain.billing

import net.pocketnai.domain.openai.OpenAiImageSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class OpenAiSpendingEntry(
    val id: String,
    val createdAt: Long,
    val status: String,
    val model: String,
    val snapshot: OpenAiImageSnapshot?,
) {
    /** Response usage is request-wide, even for batches or partially saved results. */
    val usageUsd: Double? = snapshot?.estimatedUsd?.takeIf { it.isFinite() && it >= 0 }
    val estimateMinUsd: Double? = snapshot?.estimate?.minimumUsd?.takeIf { it.isFinite() && it >= 0 }
    val estimateMaxUsd: Double? = snapshot?.estimate?.maximumUsd?.takeIf { it.isFinite() && it >= 0 }
}

data class OpenAiSpendingDay(val date: LocalDate, val entries: List<OpenAiSpendingEntry>) {
    val usageUsd = entries.sumOf { it.usageUsd ?: 0.0 }
    val knownCount = entries.count { it.usageUsd != null }
}
data class OpenAiSpendingTrend(val date: LocalDate, val usageUsd: Double)

data class OpenAiSpendingStatistics(
    val range: AnlasStatsRange,
    val groups: List<OpenAiSpendingDay>,
    val trend: List<OpenAiSpendingTrend>,
    val totalUsageUsd: Double,
    val knownCount: Int,
    val recordCount: Int,
    val storedRecordCount: Int,
) {
    val unknownCount: Int get() = recordCount - knownCount
    companion object {
        fun calculate(entries: List<OpenAiSpendingEntry>, range: AnlasStatsRange, today: LocalDate, zone: ZoneId): OpenAiSpendingStatistics {
            val start = range.days?.let { today.minusDays(it - 1) }
            val groups = entries.distinctBy { it.id }
                .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
                .filterKeys { it <= today && (start == null || it >= start) }
                .toSortedMap(compareByDescending { it })
                .map { (date, rows) -> OpenAiSpendingDay(date, rows.sortedWith(compareByDescending<OpenAiSpendingEntry> { it.createdAt }.thenBy { it.id })) }
            val daily = groups.associate { it.date to it.usageUsd }
            val trend = if (start != null) generateSequence(start) { it.plusDays(1) }.takeWhile { it <= today }
                .map { OpenAiSpendingTrend(it, daily[it] ?: 0.0) }.toList()
            else {
                val monthly = groups.groupBy { YearMonth.from(it.date) }
                monthly.keys.minOrNull()?.let { first -> generateSequence(first) { it.plusMonths(1) }
                    .takeWhile { it <= YearMonth.from(today) }
                    .map { OpenAiSpendingTrend(it.atDay(1), monthly[it]?.sumOf { day -> day.usageUsd } ?: 0.0) }.toList() }.orEmpty()
            }
            return OpenAiSpendingStatistics(range, groups, trend, groups.sumOf { it.usageUsd }, groups.sumOf { it.knownCount },
                groups.sumOf { it.entries.size }, entries.distinctBy { it.id }.size)
        }
    }
}
