package net.pocketnai.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Anlas 点数消耗流水账目。
 *
 * 记录每次生成、局部重绘、高清放大等操作所观察到的 Anlas 消耗、操作类型及变动后余额。
 */
@Entity(
    tableName = "anlas_transactions",
    indices = [Index("createdAt")],
)
data class AnlasTransactionEntity(
    @PrimaryKey val id: String,
    val accountFingerprint: String,
    val generationId: String?,
    val actionType: String,
    val anlasSpent: Long,
    val v5AllowanceDelta: Int?,
    val balanceAfter: Long?,
    val description: String,
    val createdAt: Long,
)
