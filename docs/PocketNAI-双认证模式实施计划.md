# PocketNAI 双认证模式实施计划

> **状态：已实现（实验性）并暂停推进。** 阶段 A–F 的实现已完成并保留（见技术决策记录第六节）。
> 2026-09-14 补充确认：目标用户的主要 NovelAI 账号使用 Google SSO，未确认存在可用于
> Access Key 派生的 NovelAI 独立密码，本文的“邮箱 + 密码”方案不覆盖纯 Google SSO；
> WebView 走 SSO 的替代路径也已评估为不可行（技术决策记录第七节）。
> 这类账号请使用 Persistent Token；只有在另行证明账号具有独立密码后，才会继续推进。
>
> 目标：在保留 Persistent API Token（PST）连接方式的同时，增加“邮箱 + 密码 → 本地派生 Access Key → 换取短期 Access Token”的实验性账号登录方式。
>
> 编写日期：2026-09-14  
> 适用基线：PocketNAI 0.1.0、仅 T2I、所有 NovelAI 请求直连 `https://image.novelai.net`  
> 预期读者：负责实现、测试和验收该功能的开发者或编码助手

---

## 1. 结论与交付目标

本功能应当作为现有认证层的增量升级，不重写图片生成链路，也不引入任何第三方代理服务器。

最终用户可在连接页选择两种方式：

1. **Persistent API Token（稳定、官方推荐）**
   - 保留当前行为；
   - 用户粘贴 `pst-...`；
   - 通过只读账户请求验证后，加密保存在本机。

2. **NovelAI 账号登录（实验性、方便）**
   - 用户输入邮箱和密码；
   - PocketNAI 在设备本地派生 Access Key；
   - 只把 Access Key 发送到 NovelAI 官方 `/user/login`；
   - 保存服务器返回的短期 Access Token；
   - 原始密码和 Access Key 均不得持久化；
   - 登录过期、验证码、SSO 或协议不兼容时，引导用户改用 PST。

实现完成后，现有以下能力必须保持不变：

- 四个模型及其请求参数；
- T2I 生成功能；
- 零次自动重试；
- ZIP/PNG 校验及本地历史；
- 瀑布流、详情页、复制提示词、保存和删除；
- 生成草稿记忆；
- 质量标签和提示词拼接规则；
- 所有请求仍直接发往 NovelAI，不经过 PocketNAI 开发者或社区中转服务器。

---

## 2. 范围边界

### 2.1 本次必须完成

- 连接页双认证入口；
- NovelAI Access Key 的本地派生；
- `POST /user/login` 登录请求；
- 短期 Access Token 的加密存储；
- 既有 PST 用户的无感兼容；
- 登录专用错误分类和用户提示；
- 会话失效后的重新登录引导；
- JVM 单元测试、MockWebServer 协议测试、APK 构建、模拟器安装和界面验证；
- 文档说明账号登录的实验性质及安全边界。

### 2.2 本次明确不做

- 不做注册、找回密码、修改密码；
- 不做 Google SSO、OAuth、WebView 登录或 Cookie 导入；
- 不破解或绕过 reCAPTCHA；
- 不读取浏览器 Local Storage、Cookie 或网页登录会话；
- 不保存 NovelAI 密码；
- 不保存 Access Key 以实现自动续期；
- 不接入社区代理、反向代理或开发者自建中转服务器；
- 不同步 NovelAI 故事、预设、模块或其他加密用户数据；
- 不新增 I2I、局部重绘、Vibe Transfer、Control Tools 等生成模式；
- 不修改数据库 schema；
- 不自动发起真实图片生成，不消耗用户 Anlas；
- 不因为加入账号登录而改变现有模型默认参数。

### 2.3 产品定位

PocketNAI 是私人使用并小范围分享的第三方客户端，不上架应用商店。但“小范围分享”不等于可以降低凭据安全标准。

官方当前仍要求面向用户的第三方应用请求 Persistent API Token。因此：

- PST 在界面中标为“稳定 / 官方推荐”；
- 账号登录标为“实验性”；
- 不宣传账号登录受到 NovelAI 长期兼容保证；
- 账号登录不可用时，PST 必须始终可用。

---

## 3. 已核对的协议事实

### 3.1 官方接口

NovelAI 当前 Image API 定义包含：

```http
POST /user/login
Content-Type: application/json
Accept: application/json

{"key":"<64-character-access-key>"}
```

成功响应：

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"accessToken":"<bearer-token>"}
```

官方定义说明：

- Access Key 是由客户端根据邮箱和密码计算的 64 字符字符串；
- `key` 为必填字段；
- `email` 和 `recaptcha` 字段存在，但当前基础 Access Key 登录不应主动发送邮箱；
- 可能响应 `401`、`403`、`429` 和 `500`；
- 登录接口当前存在于 `image.novelai.net` 的 API 定义中。

### 3.2 社区实现观察

社区实现普遍采用以下流程：

```text
email + password
        ↓
BLAKE2b-128 计算 salt
        ↓
Argon2id 派生 64 字节结果
        ↓
URL-safe Base64
        ↓
截取前 64 个字符作为 Access Key
        ↓
POST /user/login
        ↓
获得 Access Token
```

社区文档把 Access Token 的当前有效期记为 30 天。PocketNAI 可以把它作为 UX 提示或测试参考，但不能把“固定 30 天”当作业务正确性的唯一依据；服务器返回的 `401` 才是凭据是否仍有效的最终判断。

### 3.3 官方建议与技术可行性之间的区别

`/user/login` 技术上存在，并不等于官方推荐第三方应用收集账号密码。官方 API 说明仍要求第三方用户端应用使用 Persistent API Token，并明确提醒不要保存用户明文凭据。

因此账号登录必须被设计成可拆除、可降级的独立认证适配器，不能侵入生成协议核心。

---

## 4. Access Key 派生规范

### 4.1 算法参数

实现时必须逐项匹配参考实现，不允许采用库的默认值：

```text
domain       = "novelai_data_access_key"
preSalt      = password.take(6) + email + domain
salt         = BLAKE2b(preSalt UTF-8, digestLength = 16 bytes)

Argon2 mode  = Argon2id
version      = 0x13 / Version 1.3
password     = password UTF-8 bytes
salt         = 上一步的 16 bytes
iterations   = 2
memoryKiB    = floor(2,000,000 / 1024) = 1953
parallelism  = 1
outputLength = 64 bytes

encoded      = URL-safe Base64(raw), 保留参考实现的 padding 行为
accessKey    = encoded.take(64)
```

注意事项：

- `1953 KiB` 不能误写成 `1954 KiB`、`2000 KiB` 或 `2,000,000 KiB`；
- 必须使用 Argon2id，不能换成 Argon2i 或 Argon2d；
- BLAKE2b 输出是 16 字节，不是 16 个十六进制字符；
- Base64 必须是 URL-safe 变体，不能用普通 Base64；
- 密码必须原样保留，包括前后空格、大小写和特殊字符；
- 邮箱输入只移除用户误输入的首尾空白；除非有新的可靠证据，不擅自转小写；
- Kotlin `String` 无法保证从 JVM 内存中彻底擦除，因此只能缩短敏感字符串生命周期，不能在文档中承诺“内存绝对无残留”。

### 4.2 加密库选择

禁止手写 BLAKE2b 或 Argon2 实现。优先选用同时提供以下能力、可在 Android API 26 运行、许可证兼容且维护状态正常的库：

- `Blake2bDigest` 或等价原语；
- `Argon2BytesGenerator` 或等价原语；
- 可显式设置 Argon2id 1.3、迭代次数、内存、并行度和输出长度。

可以优先评估 Bouncy Castle 的纯 JVM 实现，但正式加入前必须：

1. 将版本固定在 `gradle/libs.versions.toml`；
2. 检查与 Android 自带加密 Provider 是否冲突；
3. 检查 debug/release APK 体积变化；
4. 验证 R8 后派生结果不变；
5. 记录依赖许可证；
6. 不在全局注册或替换系统 Provider，直接实例化所需原语即可。

不得为了省事引入包含桌面平台原生二进制、无法可靠运行在 Android ABI 上的 Argon2 包。

### 4.3 独立测试向量

实现前先用 Aedial 参考算法针对**虚构账号和密码**生成测试向量，再把“输入 → 预期 Access Key”固化进 JVM 单元测试。

测试向量至少覆盖：

- 常规 ASCII 邮箱和密码；
- 密码少于 6 个字符；
- 密码恰好 6 个字符；
- 密码超过 6 个字符；
- 密码中包含空格、引号、反斜杠和标点；
- 邮箱中包含 `+`；
- 非 ASCII 字符；
- 空密码和空邮箱的本地拒绝逻辑。

生成测试向量时不得使用真实邮箱、真实密码、现有 PST 或真实 Access Key。测试代码中不得调用网络。

只有 Kotlin 结果与独立参考实现逐字节一致，才允许进入真实登录验证阶段。

---

## 5. 目标架构

### 5.1 凭据模型

将当前只表示 PST 的 `TokenStore` 扩展为能明确区分凭据类型的存储边界。建议结构：

```kotlin
enum class CredentialType {
    PERSISTENT_API_TOKEN,
    ACCOUNT_SESSION,
}

data class StoredCredential(
    val token: String,
    val type: CredentialType,
    val createdAtEpochMillis: Long? = null,
)

data class CredentialHint(
    val type: CredentialType,
    val length: Int,
    val fingerprint: String,
)

interface CredentialStore {
    fun hasCredential(): Boolean
    fun load(): StoredCredential?
    fun save(credential: StoredCredential)
    fun clear()
    fun hint(): CredentialHint?
}
```

字段含义：

- `token`：真正放入 `Authorization: Bearer` 的值；
- `type`：决定界面显示和失效后的引导；
- `createdAtEpochMillis`：仅用于会话信息和诊断，不作为拒绝请求的依据；
- `fingerprint`：只用于本地脱敏识别，不保存 Token 首尾片段。

如果为了降低一次性改动量而暂时保留 `TokenStore` 名称，也必须让 `load()` 返回带类型的对象，不能继续让业务层靠 Token 前缀猜测类型。

### 5.2 旧数据兼容

必须保留：

- SharedPreferences 文件名 `pocketnai_secure`；
- Android Keystore alias `pocketnai_token_key`；
- 现有 `token_iv` 和 `token_ciphertext` 的读取能力；
- `backup_rules.xml` 和 `data_extraction_rules.xml` 中的排除规则。

兼容策略：

1. 新增非敏感元数据键，例如 `credential_type` 和 `credential_created_at`；
2. 若旧安装中存在密文，但不存在 `credential_type`，按 `PERSISTENT_API_TOKEN` 解释；
3. 不要求用户重新输入既有 PST；
4. 不改变旧密文格式，不更换 Keystore alias；
5. 元数据值未知或损坏时，凭据本身可解密则按 PST 安全降级；
6. 密文或 Keystore 无法解密时，沿用现有“清理并回到未连接”的行为。

不要为了这次改造创建 Room 表或给 `generations` 加列。

### 5.3 认证 API

新增独立边界，不把登录硬塞进图片生成方法：

```kotlin
interface NovelAiAuthApi {
    suspend fun login(accessKey: String): Outcome<AccountSession>
}

data class AccountSession(
    val accessToken: String,
)
```

建议实现：

```text
data/network/NovelAiAuthApi.kt
data/network/OkHttpNovelAiAuthApi.kt
domain/auth/NovelAiAccessKeyDeriver.kt
```

约束：

- URL 固定为 `${BuildConfig.NOVELAI_API_BASE_URL}/user/login`；
- 当前 base URL 必须继续是 `https://image.novelai.net`；
- 请求 JSON 只发送 `key`；
- 不发送原始密码；
- 没有明确需要时不发送邮箱；
- 不发送设备标识、广告 ID、Android ID 或联系人信息；
- 登录请求零次自动重试；
- 登录响应体设置合理读取上限，例如 64 KiB；
- `accessToken` 缺失、为空或类型错误时按协议错误处理；
- 请求体、响应 Token、邮箱、密码、Access Key 均不得进入日志。

登录请求可以复用相同的 `RedactingHttpLogger`，但建议从现有 OkHttpClient 派生一个登录专用客户端，设置较短的整体超时，例如 60 秒；不要让账号登录继承图片生成所需的 5 分钟读取等待。

### 5.4 现有生成 API

`NovelAiApi` 的三个现有业务方法不改变协议：

```kotlin
fetchAccountStatus(bearerToken)
generateImage(bearerToken, payload, destinationZip)
suggestTags(bearerToken, model, prompt)
```

可以把参数名从 `token` 改为 `bearerToken`，以免误导，但不要为两种凭据复制两套 API 方法。

生成仓库只关心：

```text
credentialStore.load()?.token
```

它不应该知道邮箱、密码、Access Key 派生算法或登录表单状态。

---

## 6. 登录业务流程

### 6.1 PST 流程

保持现有“先验证、后保存”：

```text
用户输入 PST
  → 去除输入框首尾空白
  → GET /user/data
  → 成功：保存 PERSISTENT_API_TOKEN
  → 失败：不覆盖已有凭据
```

### 6.2 账号登录流程

```text
用户输入邮箱和密码
  → 本地校验非空
  → 禁用提交按钮，进入“正在安全登录”状态
  → 在 Dispatchers.Default 或专用后台调度器派生 Access Key
  → POST /user/login { key }
  → 201 且 accessToken 合法
  → 用该 accessToken 调用 GET /user/data
  → 验证成功后保存 ACCOUNT_SESSION
  → 清空邮箱、密码、Access Key 和临时 Token 的界面状态
  → 更新 SessionState
```

为什么仍调用 `/user/data`：

- 与现有连接成功语义保持一致；
- 验证返回 Token 确实可用于当前 image 主机；
- 避免把异常格式或仅部分可用的响应保存为已连接状态。

如果登录成功但 `/user/data` 网络失败：

- 不自动重发 `/user/login`；
- 不自动连续尝试；
- 不持久保存尚未完成验证的 Token；
- 保留邮箱可以方便用户重试，但必须清空密码；
- 提示“已取得会话，但连接验证失败，请检查网络后重新登录”。

所有敏感临时值的清理应放在 `finally` 路径，确保成功、失败和协程取消都会执行。需要承认：Kotlin/Compose 中曾创建过的不可变 `String` 无法可靠主动清零，只能做到不持久化、不日志化并尽快解除引用。

### 6.3 断开和替换

- “断开连接”删除当前加密 Token 和认证类型；
- 不影响历史图片、提示词收藏和生成草稿；
- “更换连接方式”先让用户确认，再清除旧凭据；
- 新凭据验证失败时，不应默默恢复旧凭据，也不应同时保留两个有效 Token；
- 首版只保存一个当前凭据，不做多账号管理。

---

## 7. 界面与交互

### 7.1 未连接状态

连接页使用两个清晰入口，例如 Material 3 PrimaryTabRow、SegmentedButton 或等价组件：

```text
[ Persistent Token ] [ 账号登录·实验 ]
```

默认选中 PST，因为这是当前官方推荐的第三方接入方式。应用可以记住用户上次选择的是哪个表单，但不得因此记住密码或 Access Key。

### 7.2 PST 表单

保留当前输入与验证体验，并增加简短说明：

- “稳定方式，适合长期使用”；
- “可在 NovelAI 网页的账户设置中创建”；
- “新建 PST 会使旧 PST 失效”；
- 可以提供“打开 NovelAI”按钮，但只打开官方网页，不抓取 Cookie、不自动代取 Token；
- PocketNAI 不得主动把 Token 写入剪贴板。

### 7.3 账号表单

字段：

- 邮箱：邮箱键盘；
- 密码：密码键盘、默认遮挡、提供短暂显示/隐藏按钮；
- 主按钮：“登录 NovelAI”；
- 辅助入口：“登录失败？改用 Persistent Token”。

固定说明文案应包含：

> 密码只用于在本机计算登录凭据，不会保存，也不会发送给 PocketNAI 开发者。账号登录属于实验功能；遇到验证码、SSO 或协议变化时，请改用 Persistent Token。

交互要求：

- 邮箱或密码为空时禁止提交；
- 派生和请求过程中禁止重复点击；
- 离开连接页、断开连接、登录结束或应用进入后台时清空密码；
- 不使用 `SavedStateHandle`、普通 SharedPreferences 或草稿机制保存密码；
- 不把密码放进导航参数；
- 不把邮箱和密码组合进异常消息；
- 屏幕旋转是否保留密码必须明确选择：安全优先，建议清空并让用户重输；
- 密码管理器自动填充可以后续评估，但不能通过剪贴板自动读取实现。

### 7.4 已连接状态

已连接卡片显示：

- “连接方式：Persistent Token”或“连接方式：账号会话”；
- Token 长度；
- 现有 8 字符哈希指纹；
- 账号会话显示“可能需要定期重新登录”，不承诺精确到期时间；
- 替换和删除按钮。

不得显示：

- Token 前缀或后缀；
- 邮箱全文；
- 密码；
- Access Key；
- JWT Payload 中未经验证的账户数据。

---

## 8. 错误模型

### 8.1 登录专用错误

建议新增：

```kotlin
LOGIN_CREDENTIALS_INVALID
LOGIN_VERIFICATION_REQUIRED
LOGIN_RESPONSE_INVALID
```

映射建议：

| 情况 | 错误码 | 用户提示 | 是否自动重试 |
|---|---|---|---|
| `/user/login` 返回 401 | `LOGIN_CREDENTIALS_INVALID` | 邮箱或密码不正确，或当前账号不支持此登录方式 | 否 |
| `/user/login` 返回 403 | `LOGIN_VERIFICATION_REQUIRED` | 登录需要额外验证，请先登录官网或改用 PST | 否 |
| `/user/login` 返回 429 | 既有 `RATE_LIMITED` | 登录过于频繁，请稍后手动重试 | 否 |
| 201 但无 `accessToken` | `LOGIN_RESPONSE_INVALID` | NovelAI 返回了无法识别的登录结果，请更新应用或改用 PST | 否 |
| DNS/无法连接 | `NETWORK_UNAVAILABLE` | 检查网络 | 否 |
| 登录请求超时 | `NETWORK_UNAVAILABLE` 或单独的登录超时码 | 登录未完成，请稍后手动重试 | 否 |
| `/user/data` 验证失败 | 按实际错误映射 | 不保存会话 | 否 |

登录超时和图片生成超时语义不同：登录不消耗 Anlas，不应复用带有“可能已计费”文案的 `TIMEOUT_UNCERTAIN`。可以为登录增加普通 `REQUEST_TIMEOUT`，或者由认证层把登录超时映射为明确的登录网络错误。

### 8.2 已保存凭据失效

现有生成、账户检查或标签建议返回 401 时：

- 当前类型为 `ACCOUNT_SESSION`：提示“账号会话已失效，请重新登录”；
- 当前类型为 `PERSISTENT_API_TOKEN`：提示“Persistent Token 无效、已被覆盖或已撤销”；
- 不自动调用 `/user/login`；
- 不保存密码或 Access Key 来静默刷新；
- 用户确认后清除凭据并回到连接页。

首版可以继续使用底层 `TOKEN_INVALID`，由上层结合 `CredentialType` 选择文案；不要把认证类型耦合进通用 HTTP 错误映射器。

### 8.3 403 不能一概而论

登录接口的 403 更可能意味着验证或风控；生成接口的 403 仍按现有生成错误语义处理。应为登录 API 提供单独映射器，不能直接复用当前把 `401 || 403` 都映射为 `TOKEN_INVALID` 的逻辑。

---

## 9. 安全红线

以下任何一项出现即视为验收失败：

1. 密码、Access Key、PST 或 Access Token 出现在 Logcat；
2. 敏感凭据出现在异常 `detail`、崩溃信息或分析 SDK；
3. 密码或 Access Key 写入 SharedPreferences、Room、文件、图片元数据或剪贴板；
4. 网络请求经过非 NovelAI 域名；
5. 使用 `HttpLoggingInterceptor`；
6. 在测试代码、Gradle 配置、资源文件或源码中硬编码真实凭据；
7. 自动读取 `persistent-api-token.txt`；
8. 为测试自动发起真实图片生成；
9. 登录失败后无限或指数自动重试；
10. 保存 Access Key 来自动续期，却没有经过新的安全设计与用户明确选择；
11. 改名 `pocketnai_secure` 却没有同步备份排除规则；
12. 因存储重构导致旧 PST 无法读取。

额外要求：

- `RedactingHttpLogger` 只记录方法、路径、状态码和耗时；
- `/user/login` 请求体绝不记录；
- 邮箱也不进入网络日志；
- Token 指纹继续只取不可逆哈希的短片段；
- 调试 UI 不提供“复制 Token”按钮；
- 所有敏感输入在截图中默认遮挡。

---

## 10. 文件级改造清单

以下是建议清单，允许实现时根据现有包结构微调，但不得跨越分层边界。

### 10.1 新增文件

```text
app/src/main/java/net/pocketnai/domain/auth/NovelAiAccessKeyDeriver.kt
app/src/main/java/net/pocketnai/data/network/NovelAiAuthApi.kt
app/src/main/java/net/pocketnai/data/network/OkHttpNovelAiAuthApi.kt
app/src/main/java/net/pocketnai/data/network/NovelAiAuthErrorMapper.kt

app/src/test/java/net/pocketnai/domain/auth/NovelAiAccessKeyDeriverTest.kt
app/src/test/java/net/pocketnai/data/network/OkHttpNovelAiAuthApiTest.kt
app/src/test/java/net/pocketnai/data/network/NovelAiAuthErrorMapperTest.kt
app/src/test/java/net/pocketnai/ui/connect/ConnectViewModelTest.kt
```

`domain/auth` 必须保持纯 Kotlin，不得依赖 Android API。

### 10.2 修改文件

```text
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/java/net/pocketnai/data/security/TokenStore.kt
app/src/main/java/net/pocketnai/data/repo/GenerationRepository.kt
app/src/main/java/net/pocketnai/di/AppContainer.kt
app/src/main/java/net/pocketnai/ui/connect/ConnectViewModel.kt
app/src/main/java/net/pocketnai/ui/connect/ConnectScreen.kt
app/src/main/java/net/pocketnai/ui/settings/SettingsViewModel.kt
app/src/main/java/net/pocketnai/core/AppError.kt
app/src/main/java/net/pocketnai/ui/common/ErrorMessages.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml（若项目已存在）
```

### 10.3 不应修改

除非测试证明存在直接依赖，否则不要改：

```text
NovelAiRequestBuilder.kt
ModelCatalog.kt
QualityTags.kt
PromptComposition.kt
GenerationDraftCodec.kt
PocketNaiDatabase.kt
generations 表及其 Migration
ZIP/PNG 解析与历史文件结构
```

---

## 11. 分阶段执行计划

### 阶段 A：纯算法与测试向量

任务：

- 选择并固定加密依赖；
- 实现 `NovelAiAccessKeyDeriver`；
- 添加独立测试向量；
- 检查 Unicode、短密码和特殊字符；
- 确认算法运行在线程池而非 Compose 主线程。

完成标准：

- 所有测试向量一致；
- 不引用 Android API；
- 测试中不存在真实凭据；
- 不产生网络请求。

### 阶段 B：登录协议层

任务：

- 新增 `NovelAiAuthApi`；
- 使用 `/user/login`；
- 严格解析 `accessToken`；
- 登录专用错误映射；
- 使用 MockWebServer 覆盖 201、401、403、429、500、空响应、畸形 JSON、超大响应和网络异常；
- 验证请求 JSON 只有 `key`。

完成标准：

- 测试可断言请求路径恰为 `/user/login`；
- 测试可断言没有 Authorization Header；
- 测试可断言请求里没有邮箱和密码；
- 失败不会自动发起第二次请求。

### 阶段 C：凭据存储兼容

任务：

- 引入 `CredentialType` 和 `StoredCredential`；
- 保持现有密文键和 Keystore alias；
- 旧记录缺少类型时按 PST 读取；
- 新账号会话保存 `ACCOUNT_SESSION`；
- 更新 `GenerationRepository`、设置页和 `SessionState` 的调用点。

完成标准：

- 旧 PST 不需要用户重新输入；
- 断开连接只清凭据，不删历史；
- 账号会话和 PST 都能提供同一个 Bearer Token 边界；
- 没有数据库迁移。

### 阶段 D：ViewModel 登录流程

任务：

- 将 `ConnectViewModel.UiState` 拆成明确的认证模式和字段；
- 加入账号登录流程；
- `finally` 清除密码及派生结果引用；
- 添加并发点击保护；
- 添加 ViewModel 单元测试。

至少覆盖：

- PST 验证成功后保存 PST 类型；
- PST 验证失败不保存；
- 账号登录成功且 `/user/data` 成功后保存 Session 类型；
- `/user/login` 失败不调用 `/user/data`；
- `/user/data` 失败不保存；
- 重复点击只发一个登录请求；
- 登录结束清空密码；
- 切换认证模式清空密码和错误；
- 断开连接清凭据但不影响其他本地数据。

### 阶段 E：Compose 界面

任务：

- 双入口；
- 账号登录实验说明；
- 密码遮挡及显示按钮；
- 明确的加载状态；
- 登录错误引导；
- 已连接卡片展示认证类型；
- 保持竖屏和横屏可滚动、不遮挡按钮。

完成标准：

- 1080×1920 和 1920×1080 均可操作；
- 系统返回键和模式切换不会泄漏密码；
- PST 原有流程未退化；
- 账号登录按钮不会重复提交；
- 界面没有宣称“官方支持账号登录”或“永久有效”。

### 阶段 F：失效恢复

任务：

- 根据 `CredentialType` 区分凭据失效文案；
- 401 后提供返回连接页的入口；
- 不自动登录、不自动刷新；
- 保留本地历史和草稿。

完成标准：

- Session 失效提示“重新登录”；
- PST 失效提示“重新获取或替换 PST”；
- 错误不会删除图片历史；
- 不会因 401 自动重发生成请求。

---

## 12. 测试计划

### 12.1 JVM 单元测试

必须新增或更新：

- Access Key 派生固定向量；
- 输入校验；
- 登录 JSON 构造；
- 登录响应解析；
- 登录 HTTP 错误映射；
- ViewModel 状态机；
- 凭据类型降级逻辑；
- 现有 `NovelAiErrorMapperTest` 不应因登录错误映射而退化；
- 现有所有请求构造、草稿、ZIP、提示词和模型测试继续通过。

测试方法继续使用中文名称，断言使用 Truth。

### 12.2 MockWebServer 协议测试

验证内容：

```text
POST /user/login
Content-Type: application/json
Accept: application/json
body keys == {"key"}
Authorization header 不存在
请求总次数 == 1
```

不得在断言失败输出中打印完整 `key`。可以只断言字段存在、长度为 64，并比较测试值的哈希。

### 12.3 本地安全检查

在模拟器上使用完全虚构的输入：

- 查看 Logcat，确认没有邮箱、密码、Access Key 或返回 Token；
- 检查普通首选项，不应存在密码或 Access Key；
- 检查 `pocketnai_secure` 只存在 IV、密文和非敏感元数据；
- 检查系统备份规则仍排除该文件；
- 检查已连接卡片只显示类型、长度和指纹；
- 检查离开页面后密码框为空。

### 12.4 真实账号验证边界

编码助手不得：

- 读取 `persistent-api-token.txt`；
- 读取用户密码管理器；
- 从浏览器抓取 Cookie、Local Storage 或 Token；
- 把真实凭据写进命令行、脚本或测试；
- 自动发起真实图片生成。

真实账号验证只能由用户在已安装的 APK 界面中手动输入：

1. 账号登录；
2. 登录成功后只验证连接状态；
3. 用户自行决定是否手动点一次生成；
4. 开发者检查历史结果，但不读取或导出凭据。

如果用户账号使用 Google SSO 或登录触发验证码，记录为“账号登录不适用，PST 回退正常”，不继续逆向 SSO。

---

## 13. 每阶段固定验证动作

每完成一个可独立交付的阶段，按项目约定执行：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/debug/app-debug.apk
```

若 `emulator-5558` 在线，也一并安装：

```bash
adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
```

界面阶段必须截图核对。MuMu 横竖屏可能变化，点击前先确认分辨率；截图异常时连拍两张确认不是上一帧缓存。

注意：安装和界面测试不授权自动发出真实生成请求。

---

## 14. 验收清单

### 14.1 功能验收

- [ ] 旧安装的 PST 升级后仍能直接使用；
- [ ] 新安装可选择 PST；
- [ ] 新安装可选择账号登录；
- [ ] 账号登录只请求 `image.novelai.net/user/login`；
- [ ] 登录后原有账户状态检查成功；
- [ ] 登录后现有 T2I 生成链路无需分支；
- [ ] 连接卡片能区分 PST 和账号会话；
- [ ] 账号会话失效时提示重新登录；
- [ ] PST 失效时提示重新获取 PST；
- [ ] SSO/验证码场景可以退回 PST；
- [ ] 断开连接不删除历史、收藏或草稿。

### 14.2 安全验收

- [ ] 密码不持久化；
- [ ] Access Key 不持久化；
- [ ] 所有 Token 只以 Keystore AES/GCM 密文落盘；
- [ ] 敏感值不进入日志；
- [ ] 敏感值不进入崩溃 detail；
- [ ] 敏感值不进入数据库；
- [ ] 敏感值不进入图片元数据；
- [ ] PocketNAI 不主动把凭据写进剪贴板；
- [ ] 没有第三方网络中转；
- [ ] 没有自动登录重试；
- [ ] 没有自动真实生成。

### 14.3 质量验收

- [ ] 所有 JVM 单元测试通过；
- [ ] Debug APK 构建通过；
- [ ] Release 构建至少执行一次，确认加密库经 R8 后可用；
- [ ] APK 已安装到 MuMu；
- [ ] 竖屏连接页截图核对；
- [ ] 横屏连接页截图核对；
- [ ] Logcat 脱敏核对；
- [ ] APK 体积变化已记录；
- [ ] 没有修改 Room schema；
- [ ] 没有破坏现有生成、历史和草稿测试。

---

## 15. 暂停条件

遇到以下任一情况，停止继续实现账号登录，不要用猜测绕过：

1. Kotlin 派生结果与独立参考实现不一致；
2. 官方 `/user/login` 不再接受当前算法；
3. `image.novelai.net/user/login` 返回明确的迁移或禁止信息；
4. 登录必须完成无法在安全范围内支持的验证码；
5. 需要保存密码或 Access Key 才能继续；
6. 所选 Argon2 库无法稳定支持 Android API 26 或目标 ABI；
7. R8 后派生结果变化或崩溃；
8. 旧 PST 升级兼容无法保证；
9. 任何测试或日志暴露真实凭据；
10. 实现要求把用户请求转发到第三方服务器。

暂停后应保留 PST 模式可用，并把账号登录入口标记为暂不可用或在构建中关闭。

---

## 16. 推荐提交顺序

为了便于审查和回退，建议按以下顺序拆分提交：

1. `test(auth): add independent NovelAI access-key vectors`
2. `feat(auth): implement local NovelAI access-key derivation`
3. `feat(auth): add image-host account login client`
4. `refactor(auth): store typed encrypted credentials with PST fallback`
5. `feat(connect): add dual authentication state machine`
6. `feat(connect): add PST and experimental account login UI`
7. `feat(auth): distinguish expired session and invalid PST guidance`
8. `docs(auth): document security model and manual verification`

每个提交必须能够独立通过单元测试；不得把算法、存储迁移和 UI 全塞在一个不可审查的大提交中。

---

## 17. 给执行者的最终指令

实现时遵循以下优先级：

1. 不泄漏用户凭据；
2. 不破坏现有 PST 用户；
3. 不改变图片生成协议；
4. 不自动消耗 Anlas；
5. 算法必须由测试向量证明一致；
6. 登录失败时可靠回退到 PST；
7. 最后才是减少用户点击次数。

不要把“能登录一次”当作完成。完成标准是：旧 PST 可继续使用、账号登录可安全工作、失败可解释、失效可恢复、日志无敏感数据、全部测试通过、APK 已安装并完成真实界面检查。

---

## 18. 参考资料

- [NovelAI Image API OpenAPI 定义](https://image.novelai.net/docs/doc.json)
- [NovelAI Primary API 文档](https://api.novelai.net/docs/)
- [NovelAI 官方账户设置：Persistent API Token](https://docs.novelai.net/en/text/usersettings/account/)
- [Aedial/novelai-api](https://github.com/Aedial/novelai-api)
- [Aedial Access Key 参考实现](https://raw.githubusercontent.com/Aedial/novelai-api/main/novelai_api/utils.py)
- [社区 API 行为整理](https://aedial.github.io/novelaiUKB/en/Using-the-API.html)

以上社区资料只能作为协议和测试参考。应用运行时不得依赖这些站点，也不得向它们发送任何用户凭据。
