package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.pocketnai.data.local.AnlasTransactionDao
import net.pocketnai.data.local.AnlasTransactionEntity
import net.pocketnai.data.repo.AnlasLedgerRepository
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AnlasStatisticsTest {
    @Test fun `完整流水超过百条仍按本地日期统计且空白日期不被压缩`() = runTest {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = LocalDate.of(2026, 9, 1)
        val today = start.plusDays(74)
        val rows = MutableStateFlow(emptyList<AnlasTransactionEntity>())
        val dao = object : AnlasTransactionDao {
            override fun observeAll() = rows
            override suspend fun insert(entity: AnlasTransactionEntity) { rows.value = rows.value + entity }
            override suspend fun allTransactions() = rows.value
            override suspend fun clearAll() { rows.value = emptyList() }
            override suspend fun count() = rows.value.size
        }
        var sequence = 0
        var time = 0L
        val repo = AnlasLedgerRepository(dao, { "id-${sequence++}" }, { time })
        repeat(150) { index ->
            time = start.plusDays((index % 75).toLong()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            repo.record("account-${index % 2}", "GENERATE", if (index == 0) 0 else index + 1L, "test")
        }
        // UTC 的 13 日是上海的 14 日，不能按 UTC 截断或用固定 24h 回推日期。
        time = Instant.parse("2026-11-13T16:00:00Z").toEpochMilli()
        repo.record("second", "UPSCALE", 500, "boundary", balanceAfter = 429)
        time = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        repo.record("second", "UPSCALE", 9999, "future")
        time = today.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        repo.record("second", "OTHER", -4, "invalid legacy value")
        val entries = repo.observeAll().first()
        assertThat(entries).hasSize(153)
        val all = AnlasStatistics.calculate(entries.reversed(), AnlasStatsRange.ALL, today, zone)
        assertThat(all.recordCount).isEqualTo(152)
        assertThat(all.totalSpent).isEqualTo(11824L)
        assertThat(all.activeDays).isEqualTo(75)
        assertThat(all.zeroSpendCount).isEqualTo(2)
        assertThat(all.groups.first().date).isEqualTo(today)
        assertThat(all.groups.first().entries.map { it.description }).containsExactly("invalid legacy value", "test", "test", "boundary").inOrder()
        assertThat(all.groups.first().spent).isEqualTo(725L)
        assertThat(all.trend.map { it.date }).containsExactly(start, start.plusMonths(1), start.plusMonths(2)).inOrder()
        assertThat(all.trend.sumOf { it.spent }).isEqualTo(all.totalSpent)
        assertThat(all.actions.sumOf { it.spent }).isEqualTo(all.totalSpent)
        val week = AnlasStatistics.calculate(entries, AnlasStatsRange.WEEK, today, zone)
        assertThat(week.recordCount).isEqualTo(16)
        assertThat(week.totalSpent).isEqualTo(2033L)
        assertThat(week.trend).hasSize(7)
        val sparse = AnlasStatistics.calculate(listOf(entries[150]), AnlasStatsRange.WEEK, today, zone)
        assertThat(sparse.trend.map { it.spent }).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 500L).inOrder()
        val otherZone = AnlasStatistics.calculate(listOf(entries[150]), AnlasStatsRange.WEEK, today, ZoneId.of("UTC"))
        assertThat(otherZone.groups.single().date).isEqualTo(today.minusDays(1))
        repo.clearLedger()
        assertThat(repo.observeAll().first()).isEmpty()
        assertThat(AnlasStatistics.calculate(emptyList(), AnlasStatsRange.MONTH, today, zone).averageSpent).isEqualTo(0.0)
    }
}
