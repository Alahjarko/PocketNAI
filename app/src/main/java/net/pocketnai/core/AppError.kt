package net.pocketnai.core

/**
 * 用户在界面上能看到的错误分类，对应规划书 9.1 的错误映射表。
 *
 * 该类型刻意不依赖任何 Android API，方便在 JVM 单元测试中直接断言错误映射结果；
 * 具体文案由 UI 层按 [ErrorCode] 查 strings.xml 得到。
 */
enum class ErrorCode {
    /** Token 无效或失效。 */
    TOKEN_INVALID,

    /**
     * 登录时邮箱或密码不正确。
     *
     * 与 [TOKEN_INVALID] 分开：这是"还没拿到凭据"，那是"已有凭据失效了"，
     * 两者给用户的下一步动作完全不同。
     */
    LOGIN_CREDENTIALS_INVALID,

    /**
     * 登录需要额外验证（例如人机验证、或账号只支持 SSO）。
     *
     * 出现这个错误时应引导用户改用 Persistent API Token，而不是继续尝试登录。
     */
    LOGIN_VERIFICATION_REQUIRED,

    /** 登录接口返回了无法识别的结果（缺少或类型错误的 accessToken、畸形 JSON、超大响应）。 */
    LOGIN_RESPONSE_INVALID,

    /**
     * 普通请求超时。
     *
     * 与 [TIMEOUT_UNCERTAIN] 区分：后者意味着"服务端可能已经接受并计费"，
     * 只适用于图片生成；登录不消耗 Anlas，不该复用那句让人紧张的文案。
     */
    REQUEST_TIMEOUT,

    /** 余额不足（Anlas 不够）。 */
    INSUFFICIENT_ANLAS,

    /** 当前账户或订阅不能使用所选模型。 */
    MODEL_UNAVAILABLE,

    /** 请求参数无效。 */
    INVALID_PARAMS,

    /** 请求过于频繁。 */
    RATE_LIMITED,

    /**
     * NovelAI 明确要求客户端更新（例如接口迁移到新的主机或路径）。
     *
     * 单独成一类而不是混进 [INVALID_PARAMS]：2026-09 就发生过 api.novelai.net 停止
     * 接受第三方 Persistent API Token、要求改用 image 主机的情况，当时如果归类为
     * “参数无效”，排查方向会被彻底带偏。
     */
    CLIENT_UPDATE_REQUIRED,

    /** NovelAI 服务端错误。 */
    SERVER_ERROR,

    /** 网络不可用。 */
    NETWORK_UNAVAILABLE,

    /** 请求超时或断流，服务端可能已经接受任务。 */
    TIMEOUT_UNCERTAIN,

    /** ZIP / PNG 校验失败。 */
    ZIP_INVALID,

    /** 本地存储空间不足。 */
    STORAGE_FULL,

    /** 保存到系统相册失败。 */
    SAVE_TO_GALLERY_FAILED,

    /** 未预期错误。 */
    UNKNOWN,
}

/**
 * 一次失败的领域错误。
 *
 * [correlationId] 是可以展示给用户的脱敏诊断标识；[detail] 只用于本地诊断，
 * 不允许包含 Token 或完整 Prompt 原文（见规划书第 10 节）。
 */
data class AppError(
    val code: ErrorCode,
    val correlationId: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun of(code: ErrorCode, correlationId: String? = null, detail: String? = null): AppError =
            AppError(code = code, correlationId = correlationId, detail = detail)
    }
}

/** 业务结果包装：首版刻意不使用异常做控制流。 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}
