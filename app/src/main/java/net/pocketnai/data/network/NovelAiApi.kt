package net.pocketnai.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import net.pocketnai.core.AppError
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.model.ImageModel
import java.io.File

/**
 * 流式生成对上层暴露的事件（规划书 6.3 的事件模型的传输层形态）。
 *
 * [Completed] 与 [Failed] 是流的两种收尾，二者必有其一（取消除外）。
 * [Completed.labels] 是诊断信息：本次流里出现过的事件名（去重、限量），
 * 只在结构不符时用于定位 —— 其中不含任何字段值。
 */
sealed interface GenerationStreamEvent {

    /** 中间预览图（base64）；[step] 是服务端的 `step_ix`，配合请求的 steps 可算进度。 */
    data class Intermediate(val imageBase64: String, val step: Int?) : GenerationStreamEvent

    data class Final(val imageBase64: String) : GenerationStreamEvent

    data class StreamError(val message: String) : GenerationStreamEvent

    data class Completed(
        val frames: Int,
        val unknownFrames: Int,
        val labels: List<String>,
    ) : GenerationStreamEvent

    data class Failed(val error: AppError) : GenerationStreamEvent
}

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
     * 流式生成（SSE，规划书 6.3）：请求体与 [generateImage] 相同，
     * 额外在 `parameters.stream` 里声明 `sse`（OpenAPI 的 `image.StreamingType`）。
     *
     * 事件按到达顺序发出，**中间图只用于界面预览**，最终图与普通响应里的图片等价。
     * 传输层失败（HTTP 错误、连接中断）以 [GenerationStreamEvent.Failed] 事件表达，
     * 而不是抛异常 —— 调用方需要在同一条流里统一处理"成功 / 失败 / 结束"三种收尾。
     *
     * 与它对应的**费用约束**：这也是生成端点，只能由用户动作触发；
     * 连续失败后关闭流式预览的策略由调用方（设置层）负责。
     */
    fun generateImageStream(
        token: String,
        payload: JsonObject,
    ): Flow<GenerationStreamEvent>

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

    /**
     * 图像超分放大（`/ai/upscale`）。
     *
     * 官方请求体只有 `image` / `model` / `declared_blur_sigma` 三个字段，
     * **没有倍数也没有目标尺寸** —— 服务端固定放大 4 倍（技术决策记录 §30.1）。
     *
     * 响应通常为 ZIP（与普通生成一致）或二进制 PNG。写入 [destinationFile]。
     * 该端点按源图面积扣 1-4 Anlas（`UpscaleCost`），
     * 必须且只能由用户在界面上明确确认后发起。
     */
    suspend fun upscaleImage(
        token: String,
        imageBase64: String,
        destinationFile: File,
    ): Outcome<Unit>
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
