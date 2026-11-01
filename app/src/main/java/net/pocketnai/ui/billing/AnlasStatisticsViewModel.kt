package net.pocketnai.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.AnlasLedgerRepository
import net.pocketnai.domain.billing.AnlasStatistics
import net.pocketnai.domain.billing.AnlasStatsRange
import java.time.LocalDate
import java.time.ZoneId

data class AnlasStatisticsState(val statistics: AnlasStatistics? = null, val loadFailed: Boolean = false)

class AnlasStatisticsViewModel(private val repository: AnlasLedgerRepository) : ViewModel() {
    private val range = MutableStateFlow(AnlasStatsRange.MONTH)
    private val calendar = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            emit(LocalDate.now(zone) to zone)
            delay(60_000)
        }
    }
    val state = combine(repository.observeAll(), range, calendar) { entries, selected, (today, zone) ->
        AnlasStatisticsState(AnlasStatistics.calculate(entries, selected, today, zone))
    }.catch { emit(AnlasStatisticsState(loadFailed = true)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnlasStatisticsState())

    fun setRange(value: AnlasStatsRange) { range.value = value }

    fun clear(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.clearLedger() }.isSuccess) }
    }
}
