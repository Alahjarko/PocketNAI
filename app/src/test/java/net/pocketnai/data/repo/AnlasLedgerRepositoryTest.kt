package net.pocketnai.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import net.pocketnai.data.local.AnlasTransactionDao
import net.pocketnai.data.local.AnlasTransactionEntity
import org.junit.Test

class AnlasLedgerRepositoryTest {

    private class FakeAnlasTransactionDao : AnlasTransactionDao {
        private val list = mutableListOf<AnlasTransactionEntity>()
        private val flow = MutableStateFlow<List<AnlasTransactionEntity>>(emptyList())

        override suspend fun insert(entity: AnlasTransactionEntity) {
            list.removeAll { it.id == entity.id }
            list.add(0, entity)
            flow.value = list.toList()
        }

        override fun observeRecent(limit: Int): Flow<List<AnlasTransactionEntity>> =
            flow.map { it.take(limit) }

        override suspend fun allTransactions(): List<AnlasTransactionEntity> = list.toList()

        override suspend fun clearAll() {
            list.clear()
            flow.value = emptyList()
        }

        override suspend fun count(): Int = list.size
    }

    @Test
    fun recordsAndObservesTransactions() = runTest {
        val dao = FakeAnlasTransactionDao()
        val repo = AnlasLedgerRepository(
            dao = dao,
            idGenerator = { "test-tx-1" },
            clock = { 1000L },
        )

        repo.record(
            accountFingerprint = "fp-123",
            actionType = "GENERATE",
            anlasSpent = 0L,
            description = "V4.5 Curated 纯文生图",
            generationId = "gen-1",
            balanceAfter = 500L,
        )

        val recent = repo.observeRecent(10).first()
        assertThat(recent).hasSize(1)
        assertThat(recent[0].id).isEqualTo("test-tx-1")
        assertThat(recent[0].accountFingerprint).isEqualTo("fp-123")
        assertThat(recent[0].anlasSpent).isEqualTo(0L)
        assertThat(recent[0].balanceAfter).isEqualTo(500L)
        assertThat(recent[0].description).isEqualTo("V4.5 Curated 纯文生图")

        repo.clearLedger()
        assertThat(repo.observeRecent(10).first()).isEmpty()
        assertThat(repo.count()).isEqualTo(0)
    }
}
