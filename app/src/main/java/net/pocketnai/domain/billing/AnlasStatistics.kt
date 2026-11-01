package net.pocketnai.domain.billing

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 本机观察记录，不将 0 消耗推断成免费，也不从余额反推未记录的账目。 */
data class AnlasLedgerEntry(
    val id: String,
    val actionType: String,
    val anlasSpent: Long,
    val v5AllowanceDelta: Int?,
    val balanceAfter: Long?,
    val description: String,
    val createdAt: Long,
)

enum class AnlasStatsRange(val label: String, val days: Long?) {
    WEEK("近 7 天", 7), MONTH("近 30 天", 30), ALL("全部", null),
}

data class AnlasDayGroup(val date: LocalDate, val entries: List<AnlasLedgerEntry>) {
    val spent: Long = entries.sumOf { it.anlasSpent.coerceAtLeast(0) }
}

data class AnlasTrendPoint(val date: LocalDate, val spent: Long)
data class AnlasActionTotal(val actionType: String, val spent: Long, val count: Int)

data class AnlasStatistics(
    val range: AnlasStatsRange,
    val groups: List<AnlasDayGroup>,
    val trend: List<AnlasTrendPoint>,
    val actions: List<AnlasActionTotal>,
    val totalSpent: Long,
    val recordCount: Int,
    val storedRecordCount: Int,
    val zeroSpendCount: Int,
    val activeDays: Int,
) {
    val averageSpent: Double get() = if (recordCount == 0) 0.0 else totalSpent.toDouble() / recordCount

    companion object {
        fun calculate(
            entries: List<AnlasLedgerEntry>,
            range: AnlasStatsRange,
            today: LocalDate,
            zone: ZoneId,
        ): AnlasStatistics {
            val start = range.days?.let { today.minusDays(it - 1) }
            val dated = entries.map { it to Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
                .filter { (_, date) -> !date.isAfter(today) && (start == null || !date.isBefore(start)) }
            val groups = dated.groupBy({ it.second }, { it.first })
                .toSortedMap(compareByDescending { it })
                .map { (date, rows) -> AnlasDayGroup(date, rows.sortedWith(compareByDescending<AnlasLedgerEntry> { it.createdAt }.thenBy { it.id })) }
            val selected = groups.flatMap { it.entries }
            val daily = groups.associate { it.date to it.spent }
            // 与 RikkaHub 一致：短范围按日补零，全部按月，避免把空白日期挤掉。
            val trend = if (start != null) {
                generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
                    .map { AnlasTrendPoint(it, daily[it] ?: 0L) }.toList()
            } else {
                val monthly = groups.groupBy { YearMonth.from(it.date) }
                val first = monthly.keys.minOrNull()
                if (first == null) emptyList() else generateSequence(first) { it.plusMonths(1) }
                    .takeWhile { it <= YearMonth.from(today) }
                    .map { month -> AnlasTrendPoint(month.atDay(1), monthly[month]?.sumOf { it.spent } ?: 0L) }.toList()
            }
            return AnlasStatistics(
                range = range,
                groups = groups,
                trend = trend,
                actions = selected.groupBy { it.actionType }.map { (type, rows) ->
                    AnlasActionTotal(type, rows.sumOf { it.anlasSpent.coerceAtLeast(0) }, rows.size)
                }.sortedWith(compareByDescending<AnlasActionTotal> { it.spent }.thenByDescending { it.count }.thenBy { it.actionType }),
                totalSpent = groups.sumOf { it.spent },
                recordCount = selected.size,
                storedRecordCount = entries.size,
                zeroSpendCount = selected.count { it.anlasSpent <= 0 },
                activeDays = groups.size,
            )
        }
    }
}
