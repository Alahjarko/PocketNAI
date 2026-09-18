package net.pocketnai.ui.common

import androidx.annotation.StringRes
import net.pocketnai.R
import net.pocketnai.core.ErrorCode
import net.pocketnai.data.security.CredentialType

/**
 * 错误分类到用户文案的映射（规划书 9.1）。
 *
 * 领域层只产出 [ErrorCode]，具体措辞集中在这里，方便统一语气与后续多语言。
 * 文案遵循两个原则：
 * - 说清“发生了什么”和“用户能做什么”；
 * - 对超时/断流明确告知“服务端可能已经接受任务”，不诱导用户直接重试。
 */
@StringRes
fun ErrorCode.messageRes(): Int = when (this) {
    ErrorCode.TOKEN_INVALID -> R.string.error_token_invalid
    ErrorCode.LOGIN_CREDENTIALS_INVALID -> R.string.error_login_credentials_invalid
    ErrorCode.LOGIN_VERIFICATION_REQUIRED -> R.string.error_login_verification_required
    ErrorCode.LOGIN_RESPONSE_INVALID -> R.string.error_login_response_invalid
    ErrorCode.REQUEST_TIMEOUT -> R.string.error_request_timeout
    ErrorCode.INSUFFICIENT_ANLAS -> R.string.error_insufficient_anlas
    ErrorCode.MODEL_UNAVAILABLE -> R.string.error_model_unavailable
    ErrorCode.INVALID_PARAMS -> R.string.error_invalid_params
    ErrorCode.RATE_LIMITED -> R.string.error_rate_limited
    ErrorCode.CLIENT_UPDATE_REQUIRED -> R.string.error_client_update_required
    ErrorCode.SERVER_ERROR -> R.string.error_server
    ErrorCode.NETWORK_UNAVAILABLE -> R.string.error_network
    ErrorCode.TIMEOUT_UNCERTAIN -> R.string.error_timeout_uncertain
    ErrorCode.ZIP_INVALID -> R.string.error_zip_invalid
    ErrorCode.STORAGE_FULL -> R.string.error_storage_full
    ErrorCode.SAVE_TO_GALLERY_FAILED -> R.string.error_save_to_gallery_failed
    ErrorCode.REFERENCE_DECODE_FAILED -> R.string.error_reference_decode_failed
    ErrorCode.REFERENCE_TOO_LARGE -> R.string.error_reference_too_large
    ErrorCode.REFERENCE_MISSING -> R.string.error_reference_missing
    ErrorCode.VIBE_ENCODE_FAILED -> R.string.error_vibe_encode_failed
    ErrorCode.ACCOUNT_DATA_UNAVAILABLE -> R.string.error_account_data_unavailable
    ErrorCode.ACCOUNT_RESPONSE_INVALID -> R.string.error_account_response_invalid
    ErrorCode.UPDATE_CHECK_FAILED -> R.string.error_update_check_failed
    ErrorCode.UPDATE_DOWNLOAD_FAILED -> R.string.error_update_download_failed
    ErrorCode.UPDATE_SIGNATURE_MISMATCH -> R.string.error_update_signature_mismatch
    ErrorCode.UNKNOWN -> R.string.error_unknown
}

/**
 * 凭据失效时的引导文案：同是 [ErrorCode.TOKEN_INVALID]，两种凭据该做的事完全不同。
 *
 * - 账号会话失效 → 用户有邮箱和密码，可以重新登录；
 * - PST 失效 → 用户必须去 NovelAI 网页重新生成一个 Token。
 *
 * 这就是"底层只出一个 TOKEN_INVALID，由上层结合凭据类型选文案"的落点：
 * 认证类型不耦合进通用 HTTP 错误映射器。
 */
@StringRes
fun credentialInvalidMessageRes(type: CredentialType?): Int = when (type) {
    CredentialType.ACCOUNT_SESSION -> R.string.error_session_expired
    CredentialType.PERSISTENT_API_TOKEN -> R.string.error_persistent_token_invalid
    // 类型未知（例如凭据已被清掉）时用中性文案，不猜。
    null -> R.string.error_token_invalid
}
