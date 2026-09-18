package net.pocketnai.domain.account

import net.pocketnai.data.security.CredentialType

/**
 * 已保存的 NovelAI 账号（多账号快捷切换）。
 *
 * 账号 Token 由 Keystore 统一加密保存在私有安全首选项中，这里仅暴露展示所需的非敏感元数据与脱敏指纹。
 */
data class SavedAccount(
    val id: String,
    val name: String,
    val type: CredentialType,
    val tokenFingerprint: String,
    val createdAtEpochMillis: Long,
    val isActive: Boolean,
)
