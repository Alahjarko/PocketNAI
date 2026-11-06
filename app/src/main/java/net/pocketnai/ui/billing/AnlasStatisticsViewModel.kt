package net.pocketnai.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.AnlasLedgerRepository
import net.pocketnai.domain.billing.AnlasStatistics
import net.pocketnai.domain.billing.AnlasStatsRange
import net.pocketnai.domain.billing.OpenAiSpendingEntry
import net.pocketnai.domain.billing.OpenAiSpendingStatistics
import net.pocketnai.domain.openai.OpenAiImageCodec
import net.pocketnai.data.local.GenerationDao
import java.time.LocalDate
import java.time.ZoneId

data class AnlasStatisticsState(val statistics: AnlasStatistics? = null, val loadFailed: Boolean = false,
    val openAi: OpenAiSpendingStatistics? = null)

class AnlasStatisticsViewModel(
    private val repository: AnlasLedgerRepository,
    private val savedState: SavedStateHandle,
    private val generationDao: GenerationDao,
) : ViewModel() {
    private val range = savedState.getStateFlow("range", AnlasStatsRange.MONTH.name)
    private val calendar = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            emit(LocalDate.now(zone) to zone)
            delay(60_000)
        }
    }.distinctUntilChanged()
    private val openAiEntries = generationDao.observeOpenAiSpending().map { rows -> rows.map {
        OpenAiSpendingEntry(it.generationId, it.createdAt, it.status, it.modelApiId, OpenAiImageCodec.decode(it.snapshotJson))
    } }
    val state = combine(repository.observeAll(), openAiEntries, range, calendar) { entries, openAi, selected, (today, zone) ->
        val chosenRange = AnlasStatsRange.valueOf(selected)
        AnlasStatisticsState(AnlasStatistics.calculate(entries, chosenRange, today, zone),
            openAi = OpenAiSpendingStatistics.calculate(openAi, chosenRange, today, zone))
    }.catch { emit(AnlasStatisticsState(loadFailed = true)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnlasStatisticsState())

    fun setRange(value: AnlasStatsRange) { savedState["range"] = value.name }

    fun clear(openAi: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(runCatching {
            if (openAi) generationDao.clearOpenAiSpending() else repository.clearLedger()
        }.isSuccess) }
    }
}
