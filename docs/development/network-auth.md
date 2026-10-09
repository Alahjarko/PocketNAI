# 网络、代理与账号认证

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

## 安全

- **Token 绝不进入日志、数据库、图片元数据、剪贴板或崩溃信息。** 日志只允许记录请求方法、路径、状态码与耗时。
- 不引入 OkHttp 的 `HttpLoggingInterceptor`（防止有人顺手开到 BODY 级别）。网络日志走 `RedactingHttpLogger`。
- Prompt 在日志里只记录长度与哈希指纹。
- `persistent-api-token.txt`、`local.properties` 已被 `.gitignore` 排除，不要把它们加进版本库。
- Token 所在首选项文件固定叫 `pocketnai_secure`，必须与 `res/xml/backup_rules.xml`、`data_extraction_rules.xml` 的排除项保持一致。
- LLM 的独立密钥使用 `pocketnai_llm` 与专用 Keystore alias，不能写入 NovelAI 凭据槽；连接、思考协议与真实联调的授权边界见[对话专题](chat.md)。

## 网络

- **所有请求走 `https://image.novelai.net`。** `api.novelai.net` 已拒绝第三方 Persistent API Token（返回 400 要求改用 image URL）。
- T2I 请求**零次自动重试**；超时/断流映射为 `TIMEOUT_UNCERTAIN`，必须让用户知情而不是自动重来。
- 生成必须由用户明确点击触发。**自动化流程绝不能代发真实生成请求**——那会消耗用户的 Anlas。
- **"这次用哪个 seed"只能有一份**：随机模式下由 `GenerationParams.withResolvedSeed(random)`
  抽一次，**历史记录与请求体共用同一份参数**（`request.copy(params = effectiveParams)`）。
  曾经把随机 seed 只算进落库的那一份，请求体发的是原始参数里的默认 0 ——
  同一提示词反复生成同一张图，而历史里显示着一个根本没被用过的"随机"seed。
- 请求体与数据库不一致这类问题**只有把链路串起来才看得见**，因此
  `GenerationRepository` 由 `app/src/androidTest/.../GenerationSeedAndPayloadTest` 覆盖
  （假 API + 内存 Room + 真文件存储，不联网）。改生成链路时先跑它。
- **图生图要换 `action`**：`image` 字段只在 `action = "img2img"`（或 `infill`）时被接受，
  沿用 `generate` 会得到 400 `image is not allowed for regular generations`。
  `strength` 放在 `parameters` 顶层；嵌套的 `parameters.img2img` 是 inpaint 用的，不要发。
- 默认值未经核对的字段（`noise`、`extra_noise_seed`、`add_original_image`、`color_correct`）
  **一律不发**，让服务端用它自己的默认值，比猜一个更接近官方行为。
- 余额走 `GET /user/subscription`（只读），错误映射用 `AccountReadErrorMapper`：
  **余额超时是 `REQUEST_TIMEOUT`，不是 `TIMEOUT_UNCERTAIN`**（后者那句"可能已计费"只适用于生成）。

## 网络代理（公益节点 / 自定义，2026-09-18）

- 入口在设置页；**只影响 NovelAI 的请求**（`AppContainer.novelAiClient()`），
  检查更新与 APK 下载固定直连（省代理流量，用户要求）。
- **公益节点是加密的 assets**（`public_proxy_nodes.enc`，AES-256-GCM，密钥派生自代码内固定串）：
  骗得过"随手提取"，**骗不过专业逆向** —— 把这批凭据当**可轮换**的用；
  换凭据后跑 `node scripts/encrypt-proxy-nodes.mjs <proxies.txt>` 重新生成即可。
  真正的用量/并发约束请在**代理服务商后台**设置（客户端统计只是自律）。
- **SOCKS5 认证走全局 `java.net.Authenticator`**，不是 OkHttp 的 `proxyAuthenticator`
  （后者只管 HTTP 代理的 407）。两个实测坑（2026-09-18）：
  1. 不注册 Authenticator → `SOCKS : authentication failed`，请求快速失败、流量统计不动；
  2. **Android 把 SOCKS 认证的 `requestorType` 标成 `SERVER`（不是 JDK 的 `PROXY`）** ——
     按类型过滤会吞掉凭据；现在按**端口匹配当前端点**回答认证请求。
- **"3 秒不通就换节点"**：代理 client 的 `connectTimeout = 3s`（含连代理、SOCKS 握手、
  到目标建连），失败后由 `ProxyFailoverInterceptor` 换节点重试（≤3 次）。
  实测注意：模拟器/慢网络下建连要 1.8–3.3 秒，3 秒会误杀正常连接 —— 靠重试兜底
  （首次请求可能要 1–2 次重试才成功；成功后的连接会被 OkHttp 复用）。
- **重试的安全边界（红线）**：只有"**代理连接失败**（请求未发出）"或"**幂等请求（GET/HEAD）**"
  才换节点重试；**POST（生成、登录）绝不重发** —— 宁可让用户手动重试，也不重复扣费。
- 运行时代理诊断看 logcat 的
  `PocketNai/Proxy` tag（只记结构：模式/类型/端口匹配，**不记端点与凭据**）。
- 流量：`ProxyTrafficListener`（EventListener body 计数）只挂在代理 client 上；
  公益模式每日 750 MB 上限（本机统计、跨天重置），超限后 `ProxyQuotaInterceptor`
  拒绝新请求并映射成 `PROXY_QUOTA_EXCEEDED` —— 文案要指回"今日额度"，
  绝不能静默当网络错误处理。

## 凭据与认证（双认证模式）

- **绝不允许**把密码、Access Key、PST 或 Access Token 写进日志、数据库、SharedPreferences
  （`pocketnai_secure` 里的 Keystore 密文除外）、图片元数据、剪贴板、异常 detail 或测试代码。
  也不要读 `persistent-api-token.txt`，不要代发真实登录或真实生成请求。
- **不得改动的兼容性常量**：首选项文件名 `pocketnai_secure`、Keystore alias
  `pocketnai_token_key`、密文键 `token_iv` / `token_ciphertext`。改任何一个都会让旧安装
  已保存的 PST 无法读取，必须同步 `res/xml` 的备份排除规则。
- 登录错误与生成错误是**两套映射器**，不要合并：生成接口的 403 是"凭据失效"，
  登录接口的 403 是"风控/需要额外验证"。超时同理（`REQUEST_TIMEOUT` vs `TIMEOUT_UNCERTAIN`）。
- Access Key 派生的前 6 个字符必须按 **Unicode 码位**切，不能用 `take(6)`（按 UTF-16 单元）。
  `emoji-leading` / `emoji-inside` 两个向量专门守这条。
- 派生回归保留 `NovelAiAccessKeyDeriverTest` 的普通账号与 Unicode 固定向量，期望值来自独立参考实现。
- 生成链路只认 `credentialStore.load()?.token`，不应该知道邮箱、密码或派生算法的存在。
- **多账号**（2026-09-18）：每个账号的密文存在 `account_<id>_iv` / `account_<id>_ciphertext` 等键里，
  但**顶层的 `token_iv` / `token_ciphertext` / `token_type` / `token_hint_*` 必须继续同步维护**
  （激活账号的镜像）—— 那是兼容层：旧安装升级时靠 `ensureLegacyMigrated` 建索引，
  新代码读取也不该绕过它。上一条"不得更改"的三个常量在这里依然有效。
- **"添加账号"必须走 `data/account/AccountAdder`**（2026-09-19）：两种来源 —— 粘贴 PST、
  邮箱密码登录 —— **都先过 `/user/data` 验证，再落盘、再切换**。
  早期实现把粘来的 Token 不验证就保存并切换，粘错会把会话带进"已保存但不可用"的状态；
  两条路径都别绕过验证。登录路径的顺序与连接页一致：派生 → `/user/login` → 复验 → 保存，
  登录失败不碰复验接口，复验失败不落盘。`AccountAdderTest` 保留无效 Token 不覆盖原账号的回归。
- `CredentialStore` 接口里的多账号方法带有**默认假实现**（如 `switchAccount` 直接返回 false）——
  不要依赖它们：只有 `KeystoreCredentialStore` 的实现是真的。以后加方法直接写进实现类，
  别再往接口里塞"默认返回失败"的占位实现（调用方会静默失效，且不会有编译错误提醒）。
