# PocketNAI 余额与 Anlas 费用系统实施规划

> 文档状态：可实施草案  
> 编写日期：2026-09-14  
> 适用范围：PocketNAI 当前四个模型、T2I 主链路  
> 目标读者：后续负责实现、测试和界面验收的开发者

## 0. 一页结论

这个功能应拆成两个彼此独立、但可以互相校验的子系统：

1. **余额系统**：使用只读接口 `GET https://image.novelai.net/user/subscription` 获取账户当前状态。余额接口给出订阅 Anlas、购买 Anlas，以及 V5 Opus 独立的使用限额状态。余额是服务端事实，可以准确展示。
2. **费用系统**：根据当前生成参数在本地给出“预计费用”。NovelAI 当前没有公开独立报价接口，定价公式必须先与官方网页的费用标签进行无消耗校准；未经校准的组合必须显示“费用以 NovelAI 为准”，不能猜一个整数。
3. **生成后校验**：生成前后各保留余额快照，成功后刷新余额，显示“本次观察到的余额变化”。它是辅助核对，不是正式账单，因为同一账户可能同时在网页端或其他设备发生消费。
4. **V5 必须单独处理**：Opus 用户在符合条件时可能消耗 V5 Usage Limit 而不消耗 Anlas。界面必须分别显示 `Anlas` 和 `V5 免费额度`，不能把两者合并。
5. **不扩大真实费用风险**：所有自动化测试使用假响应或 MockWebServer；不得通过真实生成探测价格。开发期间唯一允许自动验证的真实生成仍是仓库约定中的已确认免费组合。

第一版不修改 Room schema，不给历史图片持久化“实际费用”，只在内存中维护余额、预计费用和最近一次余额变化。等第一版稳定、确认确实需要长期统计后，再单独设计数据库迁移。

---

## 1. 范围

### 1.1 第一版必须支持

- 使用当前已经保存的 Bearer Token 查询余额；
- 同时兼容 Persistent API Token 与登录得到的 Access Token；
- 展示订阅 Anlas；
- 展示购买 Anlas；
- 展示总 Anlas；
- 在接口返回时展示 V5 Usage Limit；
- 展示最后成功刷新时间；
- 支持用户手动刷新；
- 连接成功后自动刷新一次；
- 应用进入前台且缓存过期时刷新一次；
- 生成明确成功后刷新一次；
- 服务端返回 402 后刷新一次；
- 根据当前参数实时计算费用展示状态；
- 支持四个现有模型：
  - `nai-diffusion-4-5-curated`
  - `nai-diffusion-4-5-full`
  - `nai-diffusion-5-curated`
  - `nai-diffusion-5-full`
- 支持 T2I；
- 支持单张与批量生成；
- 批量生成同时展示本次预计总费用和平均每张费用；
- 对无法确定的组合明确显示“费用以 NovelAI 为准”；
- 不因余额读取失败阻止用户手动生成；
- 继续以服务端 402 作为余额不足的最终结论。

### 1.2 第一版不做

- 不购买 Anlas；
- 不显示美元或人民币换算；
- 不统计累计消费报表；
- 不把余额写入 Room；
- 不修改 `generations` 表；
- 不给每张批量图片伪造一个精确整数费用；
- 不做后台持续轮询；
- 不用真实生成请求探索价格；
- 不为余额查询失败弹出打断式全屏错误；
- 不因为客户端预估余额不足而永久禁用生成按钮；
- 不包含 Img2Img、Vibe Transfer、Precise Reference、Enhance、Upscale 和 Director Tools 的费用；
- 不把 V5 Usage Limit 换算成 Anlas；
- 不承诺本地预估值是 NovelAI 的正式账单。

### 1.3 后续扩展点

第二阶段如果确有需要，可以增加：

- 在 `generations` 表上新增可空字段，保存提交时的预计费用和观察到的批次余额变化；
- 按日、月、模型统计消费；
- 参考图相关功能的附加费用；
- 定价规则远程更新；
- 导出消费记录。

上述内容不进入本次实现。

---

## 2. 已确认的官方事实

### 2.1 余额接口

官方 OpenAPI 定义了：

```http
GET /user/subscription
Authorization: Bearer <token>
Accept: application/json
```

PocketNAI 的请求主机必须保持：

```text
https://image.novelai.net
```

不得改回 `api.novelai.net`。

官方定义中的关键响应结构为：

```json
{
  "tier": 3,
  "active": true,
  "expiresAt": 1234567890,
  "isGracePeriod": false,
  "trainingStepsLeft": {
    "fixedTrainingStepsLeft": 10000,
    "purchasedTrainingSteps": 2000
  },
  "usage": {
    "percent": 87,
    "isNegative": false,
    "timeUntilNextPercent": 3600
  }
}
```

来源：

- NovelAI Image API OpenAPI：<https://image.novelai.net/docs/doc.json>
- NovelAI 订阅说明：<https://docs.novelai.net/en/subscription/>
- NovelAI FAQ：<https://docs.novelai.net/en/faq/>

### 2.2 两类 Anlas

第一版按下列含义解析，但必须在首次实现后用网页端余额进行一次人工对照：

| API 字段 | PocketNAI 含义 |
|---|---|
| `fixedTrainingStepsLeft` | 订阅 Anlas |
| `purchasedTrainingSteps` | 购买 Anlas |
| 两者之和 | Anlas 总余额 |

NovelAI 官方说明：消费时先使用订阅 Anlas，订阅 Anlas 用尽后才使用购买 Anlas。购买 Anlas 不参与每月订阅池补充计算。

`trainingStepsLeft` 是历史遗留名称。代码中的领域模型应使用 `subscriptionAnlas`、`purchasedAnlas`，不要继续把界面概念叫作 Training Steps。

### 2.3 V5 Usage Limit

`usage` 是与 Anlas 分离的 V5 Opus 使用限额状态：

- `percent`：当前额度百分比。OpenAPI 标注范围为 `[0-100+]`，所以领域层不要强行截断为 0–100；只有进度条渲染时才截断。
- `timeUntilNextPercent`：恢复下一百分比所需秒数；官方定义说明，额度已满且停止补充时可能为 0。
- `isNegative`：官方定义为为真时当前不可用。

第一版必须称它为“V5 免费额度”或“V5 使用额度”，不能称为 Anlas。

### 2.4 免费与收费的官方边界

按当前官方说明：

- Opus 用户在符合免费条件时可以单张生成而不消耗 Anlas；
- 免费条件包括单张、无基础图片、Normal 范围、Steps 不超过 28；
- 一次生成多张始终可能产生 Anlas 成本；
- V5 的符合条件生成还受独立 Usage Limit 影响；
- V5 Usage Limit 用尽后，后续符合条件的生成也会开始消耗 Anlas；
- 分辨率和 Steps 会影响 V5 Usage Limit 的消耗，像素数影响尤其明显。

客户端必须把这些规则当作“当前版本的业务规则”，而不是永恒不变的协议事实。

### 2.5 本仓库真实生成的更严格安全条件

下面这组条件是开发过程中唯一允许用于自动链路验证的真实免费组合：

| 参数 | 必须值 |
|---|---|
| 模型 | `nai-diffusion-4-5-curated` |
| 尺寸 | Normal：1216×832、832×1216 或 1024×1024 |
| Steps | 23 |
| Prompt Guidance / `scale` | 7.0 |
| 数量 | 1 |
| 模式 | 纯 T2I，无参考图 |

这条是**测试安全护栏**，不是面向所有账户的通用定价公式。不要因为官方说明允许更宽的免费范围，就让自动化去试 28 Steps、V4.5 Full、V5 或其他参数。

---

## 3. 产品语义

### 3.1 界面上的四种费用状态

费用计算器不能只返回一个整数，应返回下列状态之一：

1. `Free`：当前规则可以明确判断 Anlas 为 0。
2. `UsesV5Allowance`：预计 Anlas 为 0，但会消耗 V5 Usage Limit。
3. `EstimatedAnlas`：可以按已校准规则计算预计 Anlas。
4. `Unknown`：规则未校准、余额状态不足或出现未知模型/参数，不能可靠报价。

建议展示文案：

| 状态 | 收起态短文案 | 展开态说明 |
|---|---|---|
| `Free` | `免费` | `预计不消耗 Anlas` |
| `UsesV5Allowance` | `0 Anlas` | `将消耗 V5 免费额度` |
| `EstimatedAnlas` 单张 | `预计 17` | `预计 17 Anlas` |
| `EstimatedAnlas` 批量 | `预计 68` | `本次预计 68 Anlas，平均约 17/张` |
| `Unknown` | `费用待确认` | `当前组合的费用以 NovelAI 为准` |

### 3.2 “预计费用”与“实际费用”用词

全应用统一使用：

- 生成前：`预计费用`；
- 生成后：`本次观察到的余额变化`；
- 不使用：`精确价格`、`正式账单`、`一定扣除`。

余额差值不是官方账单，原因包括：

- 同一账户可能同时在网页端或另一设备生成；
- 余额可能在生成过程中充值或续订；
- V5 可能消耗 Usage Limit 而不是 Anlas；
- 服务端余额更新可能存在延迟；
- 客户端超时不代表服务端未接受和计费；
- 批量请求只得到批次总差值，无法证明每张图片分别扣了多少。

### 3.3 余额失败不得阻断生成

余额是辅助信息。以下状态仍允许用户手动点击生成：

- 从未成功加载余额；
- 最近一次刷新失败；
- 余额缓存已经过期；
- 费用公式对当前组合返回 `Unknown`。

本地余额不足只能给出警告，服务端 402 才是余额不足的权威结论。

---

## 4. 分层与建议文件

继续遵守当前 `core/`、`domain/` 纯 Kotlin 的约定。

建议新增：

```text
app/src/main/java/net/pocketnai/domain/billing/
├── SubscriptionBalance.kt
├── GenerationCostEstimate.kt
├── AnlasPricingContext.kt
├── AnlasCostCalculator.kt
├── PaidAnlasFormula.kt
├── NovelAiWebPricingPolicy.kt
└── ObservedBalanceChange.kt

app/src/main/java/net/pocketnai/data/network/
└── SubscriptionBalanceParser.kt

app/src/main/java/net/pocketnai/data/repo/
└── AccountBalanceRepository.kt

app/src/test/java/net/pocketnai/domain/billing/
├── AnlasCostCalculatorTest.kt
└── ObservedBalanceChangeTest.kt

app/src/test/java/net/pocketnai/data/network/
├── SubscriptionBalanceParserTest.kt
└── OkHttpNovelAiApiBalanceTest.kt

app/src/test/java/net/pocketnai/data/repo/
└── AccountBalanceRepositoryTest.kt
```

第一版不新增数据库实体和 DAO。

---

## 5. 领域模型与方法签名

### 5.1 余额模型

文件：`domain/billing/SubscriptionBalance.kt`

```kotlin
package net.pocketnai.domain.billing

data class SubscriptionBalance(
    val rawTier: Int?,
    val active: Boolean?,
    val expiresAtEpochSeconds: Long?,
    val isGracePeriod: Boolean?,
    val subscriptionAnlas: Long,
    val purchasedAnlas: Long,
    val v5UsageLimit: V5UsageLimit?,
    val fetchedAtMillis: Long,
) {
    val totalAnlas: Long
        get() = safeAdd(subscriptionAnlas, purchasedAnlas)

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

data class V5UsageLimit(
    /** 保留服务端原值；OpenAPI 允许超过 100。 */
    val rawPercent: Int,
    val isNegative: Boolean,
    val timeUntilNextPercentSeconds: Long?,
) {
    val available: Boolean get() = !isNegative && rawPercent > 0

    /** 只供进度条使用，不改变领域层原值。 */
    val progressFraction: Float
        get() = rawPercent.coerceIn(0, 100) / 100f
}
```

注意：

- Anlas 使用 `Long`，不要使用 `Int`；
- 缺失字段不要静默解释成“账户没有余额”；
- 只有在 `trainingStepsLeft` 对象存在且两个余额字段可正确解析时，才构造成功的余额对象；
- `usage` 整体缺失是合法情况，解释为当前账户没有可展示的 V5 Usage Limit；
- `usage` 存在但字段类型错误时，余额本身仍可成功，`v5UsageLimit` 设为空并记录结构异常的脱敏诊断；
- 日志不得写响应体或余额明细。

### 5.2 订阅等级

官方 OpenAPI 只公开了整数 `tier`，没有在 schema 中公开枚举含义。不要直接在多个地方散落 `tier == 3`。

```kotlin
sealed interface SubscriptionTier {
    data object None : SubscriptionTier
    data object Tablet : SubscriptionTier
    data object Scroll : SubscriptionTier
    data object Opus : SubscriptionTier
    data class Unknown(val rawValue: Int) : SubscriptionTier
}

interface SubscriptionTierResolver {
    fun resolve(rawTier: Int?, active: Boolean?): SubscriptionTier
}
```

必须先通过真实账户响应与官方网页订阅名称进行一次人工对照，再在一个唯一的 resolver 中固化整数映射。未知值必须返回 `Unknown`，不能默认当 Opus 或无订阅。

仍然不得用 `tier == 0 && active == false` 判断“不能生成”。按量购买 Anlas 的账户可以没有活跃订阅但仍能正常生成。

### 5.3 费用计算输入

文件：`domain/billing/AnlasPricingContext.kt`

```kotlin
package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ResolutionTier

data class AnlasPricingContext(
    val params: GenerationParams,
    val resolutionTier: ResolutionTier,
    val subscriptionTier: SubscriptionTier,
    val v5UsageLimit: V5UsageLimit?,
    val generationKind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
    val hasBaseImage: Boolean = false,
    val pricingPolicyVersion: String,
)

enum class GenerationKind {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,
    VIBE_TRANSFER,
    PRECISE_REFERENCE,
    OTHER,
}
```

虽然第一版只支持 T2I，仍显式传入 `generationKind` 和 `hasBaseImage`，避免以后加入参考图后旧计算器误把它当成普通免费 T2I。

### 5.4 费用计算结果

文件：`domain/billing/GenerationCostEstimate.kt`

```kotlin
package net.pocketnai.domain.billing

sealed interface GenerationCostEstimate {
    val policyVersion: String

    data class Free(
        override val policyVersion: String,
        val reason: FreeReason,
    ) : GenerationCostEstimate

    data class UsesV5Allowance(
        override val policyVersion: String,
        /** 没有可靠公式时允许为空。 */
        val estimatedPercentCost: Double?,
    ) : GenerationCostEstimate

    data class EstimatedAnlas(
        override val policyVersion: String,
        val batchTotal: Long,
        val imageCount: Int,
    ) : GenerationCostEstimate {
        val averagePerImage: Double
            get() = if (imageCount > 0) batchTotal.toDouble() / imageCount else 0.0
    }

    data class Unknown(
        override val policyVersion: String,
        val reason: UnknownCostReason,
    ) : GenerationCostEstimate
}

enum class FreeReason {
    OPUS_V45_ELIGIBLE,
    OTHER_VERIFIED_RULE,
}

enum class UnknownCostReason {
    PRICING_NOT_CALIBRATED,
    UNKNOWN_SUBSCRIPTION_TIER,
    UNSUPPORTED_MODEL,
    UNSUPPORTED_GENERATION_KIND,
    INVALID_PARAMETERS,
    V5_ALLOWANCE_STATE_UNKNOWN,
    V5_ALLOWANCE_TOO_LOW_TO_CONFIRM,
}
```

### 5.5 付费公式边界

不要把网页公式直接散落进 `AnlasCostCalculator`。定义可替换策略：

```kotlin
interface PaidAnlasFormula {
    val version: String

    fun supports(context: AnlasPricingContext): Boolean

    /**
     * 返回一次请求的总 Anlas，而不是单张价格。
     * 只有经过网页版费用标签矩阵校准的实现才允许返回整数。
     */
    fun calculateBatchTotal(context: AnlasPricingContext): Long
}
```

实现文件命名示例：

```kotlin
class NovelAiWebPricingPolicy20260914 : PaidAnlasFormula {
    override val version: String = "novelai-web-2026-09-14"

    override fun supports(context: AnlasPricingContext): Boolean {
        // 只对已完成校准矩阵的四模型 T2I 组合返回 true。
        TODO()
    }

    override fun calculateBatchTotal(context: AnlasPricingContext): Long {
        // 必须逐项对应已核对的网页版价格标签和取整方式。
        // 未完成校准前不允许凭经验填写公式。
        TODO()
    }
}
```

### 5.6 总费用计算器

文件：`domain/billing/AnlasCostCalculator.kt`

```kotlin
class AnlasCostCalculator(
    private val tierResolver: SubscriptionTierResolver,
    private val paidFormula: PaidAnlasFormula,
) {
    fun estimate(context: AnlasPricingContext): GenerationCostEstimate {
        val validationFailure = validate(context)
        if (validationFailure != null) {
            return GenerationCostEstimate.Unknown(
                policyVersion = paidFormula.version,
                reason = validationFailure,
            )
        }

        if (isVerifiedFreeV45(context)) {
            return GenerationCostEstimate.Free(
                policyVersion = paidFormula.version,
                reason = FreeReason.OPUS_V45_ELIGIBLE,
            )
        }

        if (isEligibleV5UsageGeneration(context)) {
            val usage = context.v5UsageLimit
                ?: return GenerationCostEstimate.Unknown(
                    policyVersion = paidFormula.version,
                    reason = UnknownCostReason.V5_ALLOWANCE_STATE_UNKNOWN,
                )

            if (usage.available) {
                return GenerationCostEstimate.UsesV5Allowance(
                    policyVersion = paidFormula.version,
                    estimatedPercentCost = estimateV5PercentCostOrNull(context),
                )
            }
            // V5 额度不可用时继续进入 Anlas 计价。
        }

        if (!paidFormula.supports(context)) {
            return GenerationCostEstimate.Unknown(
                policyVersion = paidFormula.version,
                reason = UnknownCostReason.PRICING_NOT_CALIBRATED,
            )
        }

        return GenerationCostEstimate.EstimatedAnlas(
            policyVersion = paidFormula.version,
            batchTotal = paidFormula.calculateBatchTotal(context),
            imageCount = context.params.sampleCount,
        )
    }

    private fun validate(context: AnlasPricingContext): UnknownCostReason? {
        if (context.generationKind != GenerationKind.TEXT_TO_IMAGE || context.hasBaseImage) {
            return UnknownCostReason.UNSUPPORTED_GENERATION_KIND
        }
        if (context.params.sampleCount <= 0 ||
            context.params.steps <= 0 ||
            context.params.size.width <= 0 ||
            context.params.size.height <= 0
        ) {
            return UnknownCostReason.INVALID_PARAMETERS
        }
        return null
    }

    private fun isVerifiedFreeV45(context: AnlasPricingContext): Boolean {
        // 仅实现经过官方规则和网页版显示共同确认的条件。
        // 不把“V4.5 或更低”错误扩大到 V5。
        TODO()
    }

    private fun isEligibleV5UsageGeneration(context: AnlasPricingContext): Boolean {
        // 至少检查：Opus、V5、单张、Normal、Steps <= 28、纯 T2I、无基础图。
        TODO()
    }

    private fun estimateV5PercentCostOrNull(context: AnlasPricingContext): Double? {
        // 只有网页版 V5 百分比消耗公式也经过校准时才返回数值。
        // 否则返回 null，界面只说“将消耗 V5 免费额度”。
        return null
    }
}
```

### 5.7 为什么 Guidance 不进入普通付费公式

目前没有官方依据证明 Prompt Guidance 会改变普通 Anlas 价格。它仍必须存在于生成参数中，但不能凭经验加入收费公式。

仓库约定中 `scale = 7.0` 是开发验证账号的费用安全条件，因此真实链路验证仍要检查它；这不等于面向所有用户的通用定价公式必须按 Guidance 计费。

---

## 6. 网络层设计

### 6.1 NovelAiApi 新方法

在现有 `NovelAiApi` 中新增：

```kotlin
suspend fun fetchSubscriptionBalance(
    token: String,
): Outcome<SubscriptionBalance>
```

不要替换或改变现有：

```kotlin
suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus>
```

两者职责不同：

- `/user/data`：验证凭据和读取登录方式；
- `/user/subscription`：读取余额、订阅状态和 V5 Usage Limit。

### 6.2 OkHttp 实现

在 `OkHttpNovelAiApi` 中新增：

```kotlin
override suspend fun fetchSubscriptionBalance(
    token: String,
): Outcome<SubscriptionBalance> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$baseUrl/user/subscription")
        .get()
        .header(HEADER_AUTHORIZATION, bearer(token))
        .header(HEADER_ACCEPT, "application/json")
        .build()

    try {
        client.newCall(request).execute().use { response ->
            val correlationId = correlationIdOf(response)
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Outcome.Failure(
                    AccountReadErrorMapper.fromHttpStatus(
                        statusCode = response.code,
                        body = body,
                        correlationId = correlationId,
                    ),
                )
            }

            SubscriptionBalanceParser.parse(
                body = body,
                json = json,
                fetchedAtMillis = System.currentTimeMillis(),
            )
        }
    } catch (error: IOException) {
        Outcome.Failure(AccountReadErrorMapper.fromTransportError(error))
    }
}
```

上面是结构示例，实现时继续使用项目已有的常量和辅助方法，不复制第二份 Bearer、Correlation ID 或日志逻辑。

### 6.3 响应解析器

建议解析器返回 `Outcome`，不要在畸形响应时构造“0 余额”：

```kotlin
object SubscriptionBalanceParser {
    fun parse(
        body: String,
        json: Json,
        fetchedAtMillis: Long,
    ): Outcome<SubscriptionBalance> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                return Outcome.Failure(
                    AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID),
                )
            }

        val training = root["trainingStepsLeft"]?.jsonObject
            ?: return Outcome.Failure(
                AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID),
            )

        val subscription = training.longOrNull("fixedTrainingStepsLeft")
            ?: return Outcome.Failure(
                AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID),
            )
        val purchased = training.longOrNull("purchasedTrainingSteps")
            ?: return Outcome.Failure(
                AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID),
            )

        if (subscription < 0 || purchased < 0) {
            return Outcome.Failure(
                AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID),
            )
        }

        return Outcome.Success(
            SubscriptionBalance(
                rawTier = root.intOrNull("tier"),
                active = root.booleanOrNull("active"),
                expiresAtEpochSeconds = root.longOrNull("expiresAt"),
                isGracePeriod = root.booleanOrNull("isGracePeriod"),
                subscriptionAnlas = subscription,
                purchasedAnlas = purchased,
                v5UsageLimit = parseUsageOrNull(root["usage"]),
                fetchedAtMillis = fetchedAtMillis,
            ),
        )
    }
}
```

具体 JSON 扩展方法应安全检查类型，禁止直接使用会在字段类型错误时抛异常的强制属性链。

### 6.4 错误映射

余额读取不是登录请求，也不是生成请求。不要把三者的错误映射器合并。

建议新增 `AccountReadErrorMapper`，最低映射：

| 情况 | 结果 |
|---|---|
| 401 | `TOKEN_INVALID` |
| 408、SocketTimeout | `REQUEST_TIMEOUT` |
| 429 | `RATE_LIMITED` |
| 5xx | `SERVER_ERROR` |
| 200 但 JSON 畸形/缺关键余额字段 | `ACCOUNT_RESPONSE_INVALID` |
| 其他 4xx | `ACCOUNT_DATA_UNAVAILABLE` |
| 无网络 | `NETWORK_UNAVAILABLE` |

需要在 `ErrorCode` 新增：

```kotlin
ACCOUNT_DATA_UNAVAILABLE,
ACCOUNT_RESPONSE_INVALID,
```

余额错误只更新余额区域，不要覆盖生成页当前已有的生成错误。

### 6.5 网络安全

- Token 只进入 Authorization 请求头；
- 不把 Token、完整响应体或余额值写入日志；
- 不引入 `HttpLoggingInterceptor`；
- 继续走 `RedactingHttpLogger`；
- 日志最多记录请求方法、路径、状态码、耗时和 correlation ID；
- 测试 Token 使用明显的假字符串；
- 不读取 `persistent-api-token.txt`；
- 不把余额或 Token 放进异常 detail；
- 余额请求不得触发登录、生成或购买行为。

---

## 7. AccountBalanceRepository

余额不要直接塞进 `GenerationRepository`。生成仓库负责生成任务、文件和历史；余额是账户级状态，设置页和生成页都可能消费。

### 7.1 状态模型

```kotlin
sealed interface BalanceState {
    data object Unavailable : BalanceState

    data class Loading(
        val previous: SubscriptionBalance?,
    ) : BalanceState

    data class Available(
        val balance: SubscriptionBalance,
        val stale: Boolean,
    ) : BalanceState

    data class RefreshFailed(
        val previous: SubscriptionBalance?,
        val error: AppError,
        val failedAtMillis: Long,
    ) : BalanceState
}

enum class BalanceRefreshReason {
    CONNECTED,
    APP_FOREGROUND,
    USER_REQUESTED,
    GENERATION_COMPLETED,
    INSUFFICIENT_ANLAS,
}
```

### 7.2 仓库签名

```kotlin
class AccountBalanceRepository(
    private val api: NovelAiApi,
    private val credentialStore: CredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<BalanceState>(BalanceState.Unavailable)
    val state: StateFlow<BalanceState> = _state.asStateFlow()

    suspend fun refresh(
        reason: BalanceRefreshReason,
        force: Boolean = false,
    ): Outcome<SubscriptionBalance>

    fun latestOrNull(): SubscriptionBalance?

    fun invalidate()

    fun clear()

    fun isFresh(
        balance: SubscriptionBalance,
        maxAgeMillis: Long = BALANCE_FRESH_MS,
    ): Boolean
}
```

### 7.3 刷新规则

- `USER_REQUESTED` 总是 `force = true`；
- `CONNECTED` 刷新一次；
- `APP_FOREGROUND` 只在上次成功刷新超过 5 分钟时执行；
- `GENERATION_COMPLETED` 刷新一次，不受 5 分钟缓存限制；
- `INSUFFICIENT_ANLAS` 刷新一次，不受缓存限制；
- 同时到来的多个刷新请求合并为一个实际 HTTP 请求；
- 失败时保留上一次成功值，并标记为旧数据；
- 401 时不立刻删除 Keystore 中的凭据，先让现有会话统一进入“需要重新连接”状态；
- 切换或清除凭据时必须调用 `clear()`，避免把上一个账号的余额短暂显示给下一个账号。

可以用 `Mutex` 或共享中的 `Deferred` 合并并发刷新，禁止为每个 Compose 收集者各发一次请求。

### 7.4 缓存边界

第一版只在内存缓存：

```kotlin
private const val BALANCE_FRESH_MS = 5 * 60 * 1000L
private const val OBSERVED_COST_MAX_PRE_SNAPSHOT_AGE_MS = 2 * 60 * 1000L
```

余额不进入 SharedPreferences 和 Room，避免旧账号信息跨会话残留。应用重启后重新读取即可。

---

## 8. 生成链路集成

### 8.1 不阻塞生成

点击生成时，不应该为了获取余额而等待一个可能超时的 GET。推荐顺序：

1. 获取 `AccountBalanceRepository.latestOrNull()` 作为生成前快照；
2. 冻结当前生成参数；
3. 计算并保留本次预计费用；
4. 按原流程立即提交生成；
5. 生成明确完成后强制刷新余额；
6. 若前后快照均有效，计算观察到的变化；
7. 更新界面一次性结果。

如果生成前快照不存在或超过 2 分钟，不计算“本次观察到的余额变化”，只显示生成后余额。

### 8.2 生成事件

不建议让网络层直接承担余额差值。由 `GenerateViewModel` 或独立协调器在 `GenerationEvent` 外围处理：

```kotlin
data class GenerationBillingSession(
    val generationId: String?,
    val estimate: GenerationCostEstimate,
    val before: SubscriptionBalance?,
    val startedAtMillis: Long,
)
```

建议给 `GenerateViewModel.UiState` 增加：

```kotlin
val balanceState: BalanceState = BalanceState.Unavailable,
val costEstimate: GenerationCostEstimate,
val lastObservedChange: ObservedBalanceChange? = null,
```

`costEstimate` 必须由 `params + balanceState` 派生，不要写入 `GenerationDraftCodec`。它不是用户草稿字段，也不应该随参数偏好持久化。

### 8.3 完成后的刷新

当收到 `GenerationEvent.Completed`：

```kotlin
val afterResult = accountBalanceRepository.refresh(
    reason = BalanceRefreshReason.GENERATION_COMPLETED,
    force = true,
)

val observed = when (afterResult) {
    is Outcome.Success -> ObservedBalanceChange.calculate(
        before = billingSession.before,
        after = afterResult.value,
        expectedImageCount = frozen.params.sampleCount,
        generationCompleted = true,
    )
    is Outcome.Failure -> null
}
```

当收到 402：

- 保留当前生成错误 `INSUFFICIENT_ANLAS`；
- 另外刷新余额；
- 余额刷新失败不能覆盖 402；
- 不自动重新生成。

当收到 `TIMEOUT_UNCERTAIN`：

- 不计算确定费用；
- 可以刷新余额，但结果只显示为“余额变化待确认”；
- 不自动重发生成请求；
- 提示用户检查 NovelAI 网页历史或稍后手动刷新。

### 8.4 部分成功

如果请求返回 `GenerationStatus.PARTIAL`：

- 仍可刷新余额；
- 显示批次总余额变化；
- 不把总变化除以“实际收到的张数”后称为精确单张成本；
- 可显示：`本次余额减少 34 Anlas；请求 4 张，收到 2 张`。

---

## 9. 观察到的余额变化

文件：`domain/billing/ObservedBalanceChange.kt`

```kotlin
sealed interface ObservedBalanceChange {
    data class AnlasDecreased(
        val subscriptionSpent: Long,
        val purchasedSpent: Long,
        val totalSpent: Long,
        val requestedImageCount: Int,
    ) : ObservedBalanceChange {
        val averagePerRequestedImage: Double?
            get() = requestedImageCount.takeIf { it > 0 }
                ?.let { totalSpent.toDouble() / it }
    }

    data class V5AllowanceChanged(
        val beforePercent: Int,
        val afterPercent: Int,
        val anlasSpent: Long,
    ) : ObservedBalanceChange

    data class NoVisibleChange(
        val mayBeDelayed: Boolean,
    ) : ObservedBalanceChange

    data class Ambiguous(
        val reason: AmbiguousBalanceReason,
    ) : ObservedBalanceChange

    companion object {
        fun calculate(
            before: SubscriptionBalance?,
            after: SubscriptionBalance?,
            expectedImageCount: Int,
            generationCompleted: Boolean,
        ): ObservedBalanceChange
    }
}

enum class AmbiguousBalanceReason {
    MISSING_BEFORE_SNAPSHOT,
    MISSING_AFTER_SNAPSHOT,
    GENERATION_NOT_CONFIRMED,
    BALANCE_INCREASED_DURING_REQUEST,
    ACCOUNT_CHANGED,
    SNAPSHOT_TOO_OLD,
}
```

计算原则：

```kotlin
val subscriptionSpent = (before.subscriptionAnlas - after.subscriptionAnlas)
    .coerceAtLeast(0)
val purchasedSpent = (before.purchasedAnlas - after.purchasedAnlas)
    .coerceAtLeast(0)
val totalSpent = subscriptionSpent + purchasedSpent
```

但在计算前必须检查：

- 前后快照属于同一凭据会话；
- 前快照足够新；
- 生成已明确完成；
- 两个余额池没有在期间增加；
- 所有数值有效。

如果订阅或购买余额增加，可能发生续订、充值或其他并发操作，返回 `Ambiguous`，不能使用 `coerceAtLeast(0)` 掩盖异常。

V5 百分比是整数，前后相同不等于没有消耗，可能只是取整后未变化。因此 `NoVisibleChange` 的文案必须是“暂未观察到余额变化”，不能是“本次完全免费”。

---

## 10. 定价公式的校准流程

### 10.1 原则

当前没有公开的服务端报价接口。实现者不得：

- 根据旧版社区库直接照抄公式；
- 从“默认一张大约 17 Anlas”反推全部参数；
- 用真实生成逐个试价；
- 假设 Curated 与 Full 一定同价；
- 假设总价永远等于单价乘张数；
- 假设 V4.5 和 V5 使用完全相同公式；
- 假设小数取整方向。

### 10.2 安全采样方式

使用 NovelAI 官方网页，只调整参数并读取生成按钮附近的费用标签，**绝不点击生成**。

建议建立临时核对表：

```csv
captured_at,account_tier,model,resolution_tier,width,height,steps,sample_count,web_anlas_label,v5_usage_state,notes
```

不得记录：

- Token；
- Cookie；
- 邮箱；
- 用户名；
- Prompt 内容；
- 任何可用于登录的值。

### 10.3 最低校准矩阵

四个模型分别核对：

- Normal 横、竖、方；
- Large 横、竖、方；
- Steps：23、28、29、40、50；
- 数量：1、2、4。

为了减少组合数量，可以先使用正交矩阵：

1. 固定 Normal 方形、Steps 23、单张，对四模型核对；
2. 固定一个模型、Steps 23、单张，对六种尺寸核对；
3. 固定一个模型、Normal 方形、单张，对五个 Steps 核对；
4. 固定一个模型、Normal 方形、Steps 23，对数量 1/2/4 核对；
5. 用第二个模型重复边界点，判断模型倍率是否一致；
6. 对所有临界条件额外核对：28→29、Normal→Large、1→2；
7. 最后用至少 10 个未参与拟合的组合做交叉验证。

### 10.4 公式进入代码的门槛

只有同时满足下列条件，`PaidAnlasFormula.supports()` 才能对某组参数返回 `true`：

- 公式能解释全部校准样本；
- 所有交叉验证样本与网页标签完全一致；
- 取整方向已确认；
- 批量总价已确认；
- 模型差异已确认；
- 免费条件边界已确认；
- 公式写有版本与核对日期；
- 单元测试覆盖全部样本。

没有通过的组合统一返回：

```kotlin
GenerationCostEstimate.Unknown(
    policyVersion = paidFormula.version,
    reason = UnknownCostReason.PRICING_NOT_CALIBRATED,
)
```

### 10.5 V5 百分比费用

如果网页只显示“使用额度”而不显示一次将消耗多少百分比，第一版不要预测具体百分比，只显示：

```text
0 Anlas · 将消耗 V5 免费额度
```

只有找到并验证官方网页的百分比计算逻辑后，才能显示：

```text
预计消耗约 2.4% V5 免费额度
```

绝不能把百分比换算成 Anlas。

---

## 11. UI 集成

### 11.1 生成悬浮层头部

保持现有 `GenerateButton` 在悬浮层头部右侧，不移动按钮。

建议在现有参数摘要或按钮附近增加紧凑费用文本：

```text
图片生成                         预计 17  [生成]
V5 Curated · 1024×1024 · 默认质量
Anlas 12,000 · V5 额度 87%
```

收起态仍然要能看到费用和余额，但第三行的错误优先级不得被余额覆盖。现有 `SheetStatusLine` 保持：

```text
未连接 → 生成中 → 失败 → 提示词为空 → 拖动提示
```

余额和费用应成为独立的小型信息区域，不要塞进这条错误状态优先级。

### 11.2 余额详情弹层

点击余额区域打开 ModalBottomSheet 或 Dialog：

```text
账户余额

订阅 Anlas          10,000
购买 Anlas           2,000
总余额              12,000

V5 免费额度             87%
约 1 小时恢复 1%

最后更新            14:32
                         [刷新]
```

状态文案：

- 加载中且有旧数据：`正在刷新，当前显示上次结果`；
- 加载中且无旧数据：`正在读取余额…`；
- 失败且有旧数据：`刷新失败，当前显示 14:32 的结果`；
- 从未成功：`余额暂不可用`；
- 无 `usage`：隐藏整个 V5 区域；
- `isNegative = true`：`V5 免费额度当前不可用`。

### 11.3 生成按钮行为

- `EstimatedAnlas.batchTotal > totalAnlas` 且余额新鲜：点击时弹出非阻断警告，用户仍可继续尝试；
- 余额未知或过期：不禁用生成；
- `Unknown`：不禁用生成；
- 402：沿用现有余额不足错误；
- 不得因为本地公式错误让用户永远无法提交请求。

建议警告：

```text
当前显示余额可能不足

本次预计需要 68 Anlas，最近读取到的余额为 34。
最终费用和是否可生成以 NovelAI 返回结果为准。

[取消] [仍然生成]
```

### 11.4 设置页

设置页可以复用同一个 `AccountBalanceRepository.state` 展示完整余额，不创建第二份网络状态或独立缓存。

断开连接时：

```kotlin
credentialStore.clear()
sessionState.update(false)
accountBalanceRepository.clear()
```

必须立即清除内存中的旧账号余额。

---

## 12. 测试计划

### 12.1 SubscriptionBalanceParserTest

至少覆盖：

- 完整响应正确解析两个 Anlas 池；
- `usage` 正确解析；
- `usage` 缺失仍能解析余额；
- 未知字段被忽略；
- `tier` 缺失时保留为空；
- `active` 缺失时保留为空；
- `trainingStepsLeft` 缺失返回响应无效；
- 任一关键余额字段缺失返回响应无效；
- 余额字段是字符串时不接受；
- 余额为负数时不接受；
- 空响应不接受；
- HTML 错误页不接受；
- `percent > 100` 保留原值；
- `timeUntilNextPercent = 0` 合法；
- 响应中出现伪造 Token 字段时不进入错误 detail 或日志。

### 12.2 OkHttpNovelAiApiBalanceTest

使用 MockWebServer：

- 请求方法是 GET；
- 路径是 `/user/subscription`；
- Authorization 使用 Bearer；
- Accept 是 JSON；
- 200 返回余额；
- 401 映射 Token 无效；
- 429 映射请求频繁；
- 500 映射服务端错误；
- 超时映射普通请求超时，不是 `TIMEOUT_UNCERTAIN`；
- 日志不包含 Authorization、假 Token、响应体或余额值；
- 测试不访问真实 NovelAI 网络。

### 12.3 AccountBalanceRepositoryTest

- 无凭据时不发网络请求；
- 首次连接会刷新；
- 5 分钟内前台事件不重复刷新；
- 用户手动刷新无视缓存；
- 生成完成后强制刷新；
- 402 后强制刷新；
- 两个并发刷新合并为一次请求；
- 失败保留旧余额并标记过期；
- 切换账号清除旧余额；
- 401 不泄露 Token；
- `clear()` 后状态回到 `Unavailable`。

### 12.4 AnlasCostCalculatorTest

所有测试名使用中文，至少覆盖：

- V4.5 符合已验证免费规则时返回 Free；
- V4.5 批量不误判为免费；
- V4.5 Large 不误判为免费；
- Steps 28 与 29 边界；
- V5 Opus 且额度可用返回 UsesV5Allowance；
- V5 额度不可用时进入 Anlas 计价；
- V5 没有 usage 状态时返回 Unknown，而不是免费；
- 非 Opus 不走 Opus 免费规则；
- 未知 tier 返回 Unknown 或按已校准付费规则处理，不冒充 Opus；
- Img2Img/参考图不套用 T2I 免费规则；
- 四模型的校准样本全部匹配网页标签；
- 批量总价匹配网页标签；
- 平均每张允许小数；
- 超大数计算不溢出；
- 非法参数返回 Unknown。

### 12.5 ObservedBalanceChangeTest

- 只消耗订阅 Anlas；
- 订阅池耗尽并继续消耗购买 Anlas；
- 只消耗购买 Anlas；
- Anlas 不变但 V5 百分比下降；
- 两者都不变返回 NoVisibleChange；
- 生成期间充值导致余额增加返回 Ambiguous；
- 缺少前快照返回 Ambiguous；
- 超时不返回确定消费；
- 批量只给总变化和平均值；
- V5 整数百分比没有变化时不宣称完全免费。

### 12.6 ViewModel/UI 测试

- 参数变化立即重算预计费用，不发网络请求；
- 余额变化会重算免费/收费状态；
- 余额刷新失败不覆盖生成错误；
- 生成错误不清除最后成功余额；
- 余额未知时生成按钮仍可用；
- 本地预计余额不足时出现确认提示；
- 402 文案仍正确；
- `TIMEOUT_UNCERTAIN` 显示可能已计费；
- 无 V5 usage 时不显示 0%；
- 批量显示总价和平均价；
- 断开连接后旧余额立即消失。

---

## 13. 实施顺序

### 阶段 0：只读协议与网页费用核对

1. 用已有登录状态人工读取 `/user/subscription`，只核对字段结构和值与网页余额是否一致；
2. 不打印 Authorization 和完整响应；
3. 确认 `fixedTrainingStepsLeft` / `purchasedTrainingSteps` 映射；
4. 确认当前账号的 `tier` 整数与网页订阅名称；
5. 确认 `usage.percent` 是剩余额度而不是已用额度；
6. 按第 10 节记录网页费用标签矩阵；
7. 不点击生成。

阶段 0 没完成前，可以先实现余额读取；费用计算器只能返回 `Unknown` 或已经明确验证的免费状态。

### 阶段 1：余额领域模型与解析器

1. 新增领域模型；
2. 新增解析器；
3. 新增网络方法；
4. 完成解析器和 MockWebServer 测试；
5. 确认日志脱敏。

### 阶段 2：余额仓库与生命周期

1. 新增 `AccountBalanceRepository`；
2. 加入 `AppContainer`；
3. 连接成功后刷新；
4. 前台过期刷新；
5. 手动刷新；
6. 断开连接清除；
7. 完成并发合并和缓存测试。

### 阶段 3：费用计算器

1. 新增费用状态模型；
2. 实现已验证免费判定；
3. 把网页校准公式放入版本化策略；
4. 完成全部矩阵测试；
5. 未覆盖参数返回 `Unknown`。

### 阶段 4：生成页 UI

1. 悬浮层头部增加紧凑余额和预计费用；
2. 增加余额详情弹层；
3. 增加手动刷新；
4. 增加余额不足警告但不永久阻断；
5. 不改变现有生成按钮位置；
6. 不改变 `SheetStatusLine` 的错误优先级。

### 阶段 5：生成后核对

1. 点击生成时捕获新鲜余额快照；
2. Completed 后刷新；
3. 402 后刷新；
4. 计算观察到的余额变化；
5. 超时/断流保持不确定；
6. 批量只显示总变化和平均值。

### 阶段 6：完整验证

每次完成代码改动后严格执行仓库固定动作：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/debug/app-debug.apk
```

如果 `emulator-5558` 在线，也一并安装。

然后用截图核对：

- 收起态余额和预计费用没有挤掉生成按钮；
- 展开态余额详情可读；
- 1080×1920 与 1920×1080 两种方向没有截断；
- 生成中、失败、提示词为空的状态仍正确；
- 手动刷新有明确加载反馈；
- 断开连接后不残留旧余额。

自动化与截图验证不得代发真实生成。

---

## 14. 验收标准

### 14.1 余额

- `/user/subscription` 请求成功；
- 网页端与 PocketNAI 的订阅、购买、总 Anlas 完全一致；
- V5 Usage Limit 与网页方向一致；
- 无 usage 的账户不显示错误的 0%；
- 余额请求失败时不会显示为 0；
- 切换账号或断开连接不会泄露上一个账号余额；
- 余额请求不会把 Token 或响应内容写入日志。

### 14.2 费用预估

- 四个模型所有已支持组合与校准矩阵一致；
- 免费、V5 Usage Limit、Anlas 计价、未知四类状态互不混淆；
- 28→29、Normal→Large、1→2 等边界正确；
- 未校准组合不显示伪精确费用；
- 批量同时显示总费用和平均每张，不伪造逐图费用；
- 定价策略带版本号和核对日期。

### 14.3 生成后校验

- 生成成功后余额自动刷新一次；
- 订阅池跨到购买池时总差值正确；
- V5 额度变化与 Anlas 变化分开展示；
- 并发、充值、续订或快照过旧时返回不确定状态；
- 超时或断流不会宣称未扣费，也不会自动重试生成。

### 14.4 回归

- 单元测试全绿；
- Debug APK 构建成功；
- APK 已安装到在线模拟器；
- 生成页参数记忆不受影响；
- 提示词补全不受影响；
- 连接、断开和双认证不受影响；
- 瀑布流和历史不受影响；
- 数据库 schema 不变。

---

## 15. 实现者不得做的事情

- 不得读取或打印 `persistent-api-token.txt`；
- 不得在测试中写真实 Token；
- 不得用真实生成探测费用公式；
- 不得自动生成任何可能收费的图片；
- 不得自动重试 T2I；
- 不得把余额查询超时映射成 `TIMEOUT_UNCERTAIN`；
- 不得把登录、余额、生成三套错误语义合并；
- 不得用 `tier == 0` 判断账户不能生成；
- 不得把 `usage.percent` 加到 Anlas 总额；
- 不得把 V5 免费额度称为 Anlas；
- 不得把余额字段缺失解释成 0；
- 不得把旧缓存账号余额展示给新账号；
- 不得在定价未校准时返回猜测数字；
- 不得为了余额功能重建 `generations` 表；
- 不得使用破坏性 Room 迁移；
- 不得移动现有生成按钮；
- 不得让余额错误覆盖生成错误；
- 不得停在“编译通过”，必须完成测试、构建、安装与必要的模拟器截图验证。

---

## 16. 最终推荐交付形态

第一版完成后，用户在生成页应该一眼得到三件事：

```text
我还有多少 Anlas？
这次预计要花多少？
刚才那次实际观察到余额变化多少？
```

同时清楚区分：

```text
Anlas 余额
V5 免费额度
本地预计费用
生成后的观察值
服务端最终判断
```

这个边界比追求一个看似精确、实际上可能因订阅规则或网页更新而错误的数字更重要。

---

## 17. 实施记录（2026-09-14）

阶段 1–5 已实现并验证。本节只记结果与遗留项，不修改上面的规划正文。

### 17.1 已完成

| 阶段 | 状态 | 说明 |
|---|---|---|
| 1 领域模型与解析器 | ✅ | `domain/billing/` 八个文件；`SubscriptionBalanceParser` + `AccountReadErrorMapper` + `NovelAiApi.fetchSubscriptionBalance` |
| 2 余额仓库 | ✅ | `AccountBalanceRepository`：缓存 5 分钟、并发合并（Mutex + 共享 Deferred）、失败保留旧值、换账号 `clear()` |
| 3 费用计算器 | ✅ | 四态 + 两条免费规则 + 参考图附加费；付费公式为未校准占位实现 |
| 4 生成页 UI | ✅ | 费用显示在**生成按钮上**（按钮同时放大为两行）；头部独立余额行（点击看明细）；余额不足仅一次非阻塞确认 |
| 5 生成后核对 | ✅ | 前后快照 + 强制刷新 + `ObservedBalanceChange`（余额上升/快照过旧/生成未确认一律"待确认"） |
| 6 完整验证 | ✅ | 350 个单元测试全绿、APK 构建、两台模拟器安装、四种状态的截图核对 |

界面相对规划 §11.1 的一处调整：**费用直接显示在生成按钮上**（账号所有者要求），
余额单独一行、费用明细放表单顶部；`SheetStatusLine` 的优先级未改动，收起态高度 118 → 146 dp。

### 17.2 真机观测结果（需要账号所有者确认）

免费档位下做了一次图生图（带 1 张起点图）：

- 生成前余额（订阅池 / 购买池）：**407 / 0**
- 生成成功后立即刷新：**407 / 0**
- 生成后约 2 分钟强制刷新：**407 / 0**

也就是说**没有观察到扣减**，界面如实显示"暂未观察到余额变化"。
两种解释都还成立：该账号对 V4.5 Curated 的全部生成都不计费（含参考图），
或服务端余额更新比 2 分钟更慢。代码目前按"每张参考图 +5"实现（按钮显示"预计 5"）。

### 17.3 遗留项

1. **付费定价矩阵未校准**：需要按 §10.3 用官方网页的费用标签做正交核对（不点生成），
   完成后把公式实现为带版本的 `PaidAnlasFormula`，`supports()` 才允许返回 true。
2. **字段映射待与网页对照**：观测到的 407 落在"订阅池"，而账号所有者描述为"买了积分"，
   需要按 §13 阶段 0 第 3 项核对一次。
3. **订阅等级整数待与网页订阅名称对照**（阶段 0 第 4 项）：当前映射是社区一致的解读，
   未知值一律 `Unknown`。
4. **`usage.percent` 的方向待确认**：观测到 0% 且"约 2 小时恢复 1%"，
   与"当前额度百分比"的解释一致，但仍建议在网页上对照一次。
5. **参考图附加费待确认**：见 §17.2，目前按钮显示"预计 5"但未观测到扣减。
