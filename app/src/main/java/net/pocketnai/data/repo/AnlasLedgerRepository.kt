package net.pocketnai.data.repo

import kotlinx.coroutines.flow.Flow
import net.pocketnai.data.local.AnlasTransactionDao
import net.pocketnai.data.local.AnlasTransactionEntity
import java.util.UUID

/**
 * Anlas 点数消耗流水账目仓库。
 *
 * 记录每次生图、放大等操作产生的点数变化、剩余余额及摘要说明，
 * 供用户在设置和余额弹窗中核对账单流水。
 */
class AnlasLedgerRepository(
    private val dao: AnlasTransactionDao,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun record(
        accountFingerprint: String,
        actionType: String,
        anlasSpent: Long,
        description: String,
        generationId: String? = null,
        v5AllowanceDelta: Int? = null,
        balanceAfter: Long? = null,
    ) {
        val entity = AnlasTransactionEntity(
            id = idGenerator(),
            accountFingerprint = accountFingerprint,
            generationId = generationId,
            actionType = actionType,
            anlasSpent = anlasSpent,
            v5AllowanceDelta = v5AllowanceDelta,
            balanceAfter = balanceAfter,
            description = description,
            createdAt = clock(),
        )
        dao.insert(entity)
    }

    fun observeRecent(limit: Int = 100): Flow<List<AnlasTransactionEntity>> =
        dao.observeRecent(limit)

    suspend fun allTransactions(): List<AnlasTransactionEntity> =
        dao.allTransactions()

    suspend fun clearLedger() {
        dao.clearAll()
    }

    suspend fun count(): Int = dao.count()
}
