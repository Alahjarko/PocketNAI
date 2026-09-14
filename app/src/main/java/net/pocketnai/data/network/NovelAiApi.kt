package net.pocketnai.data.network

import kotlinx.serialization.json.JsonObject
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.model.ImageModel
import java.io.File

/**
 * NovelAI 的接口边界（规划书 6.1）。
 *
 * 上层只依赖这个接口；`OkHttpNovelAiApi` 是首版唯一实现，
 * 测试可以用假实现替换，不需要真实网络。
 */
interface NovelAiApi {

    /**
     * 用只读账户状态请求验证 Token（规划书 4.1 第 3 步）。
     * 验证失败的 Token 不会被保存。
     */
    suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus>

    /**
     * 读取账户余额（余额规划 §6.1）。
     *
     * 与 [fetchAccountStatus] 职责不同、不能互相替代：
     * - `/user/data`：验证凭据、读取登录方式；
     * - `/user/subscription`：读取两个 Anlas 池、订阅状态与 V5 使用额度。
     *
     * 失败**不得阻断生成**：余额是辅助信息，能不能生成始终由服务端 402 决定。
     */
    suspend fun fetchSubscriptionBalance(token: String): Outcome<SubscriptionBalance>

    /**
     * 发起 T2I 生成，把 ZIP 响应流写入 [destinationZip]（规划书 6.2 第 1 步）。
     *
     * 本方法只负责传输，不解析 ZIP：解包与校验由 [ZipImageExtractor] 完成，
     * 这样网络协议变化与图片校验逻辑互不影响。
     */
    suspend fun generateImage(
        token: String,
        payload: JsonObject,
        destinationZip: File,
    ): Outcome<Unit>

    /**
     * 标签建议（规划书 8.1）。属于第二层能力，失败不允许影响正常生成。
     */
    suspend fun suggestTags(
        token: String,
        model: ImageModel,
        prompt: String,
    ): Outcome<List<String>>

    /**
     * 把一张图片编码成 Vibe Transfer 用的二进制（`.vibe`）。
     *
     * 官方网页的做法是先把图编码成 `.vibe`，再把它的 base64 放进 `reference_image_multiple`。
     * 这个接口**是否计费尚未确认**，因此调用方必须保证它只由用户动作触发
     * （选图之后），不得放在自动化流程里。
     */
    suspend fun encodeVibe(
        token: String,
        model: ImageModel,
        imageBase64: String,
        informationExtracted: Double,
    ): Outcome<ByteArray>
}

/**
 * 账户状态。
 *
 * 只有 [connected] 是可靠结论（HTTP 成功即视为 Token 有效），
 * 它也是首版唯一在界面上使用到的结论 —— `/user/data` 只用来验证 Token（规划书 4.1）。
 *
 * 已用真实账户核对（2026-09-14）：
 * - `GET https://image.novelai.net/user/data` 返回
 *   `{"subscription":{"tier":0,"active":false,...},"information":{...}}`；
 * - **`tier` 嵌在 `subscription` 下且是整数**，不是顶层字符串；
 * - 该响应里**没有** Anlas 字段。
 *
 * ⚠️ 不要用 [subscriptionActive] 判断“能否生成图片”。按量购买 Anlas 但不订阅的账户，
 * 这里同样是 `tier = 0, active = false`，却能正常生成。初版曾据此在连接页显示红色告警，
 * 那是误报（真实用户的反馈）。额度不足应该在真正提交生成、服务端返回 402 时，
 * 由 [net.pocketnai.core.ErrorCode.INSUFFICIENT_ANLAS] 如实告知。
 *
 * [tier] 与 [subscriptionActive] 目前不参与界面展示，保留它们是为了记录已核对的响应结构，
 * 供后续“账户信息”类功能使用。
 */
data class AccountStatus(
    val connected: Boolean,
    val tier: Int? = null,
    val subscriptionActive: Boolean? = null,
    /**
     * 账户的登录方式，来自 `information.loginMethod`。
     *
     * 实测值为 `"sso"` 时表示账号是通过 Google 等第三方方式注册的 ——
     * 这类账号通常没有 NovelAI 密码，因此"邮箱 + 密码派生 Access Key"的登录方式不适用，
     * 界面据此提示用户改用 Persistent Token。字段缺失时为 null，不做任何推断。
     */
    val loginMethod: String? = null,
)
