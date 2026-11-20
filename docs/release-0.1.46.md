# PocketNAI 0.1.46 发布核对

2026-11-20 经用户确认后发布 [build-46](https://github.com/Alahjarko/PocketNAI/releases/tag/build-46)。本版新增 Gemini 原生 REST 协议支持，修复 Google OpenAI 兼容端点在多模态与工具调用上的 400 失败问题，打通对话内 OpenAI 生图卡片确认流，并升级「正在思考」动效与思考过程收起顺滑度：

- **Gemini 原生协议支持（`LlmDialect.GEMINI`）**：新增 `GeminiProtocol` 与 `GeminiStreamAccumulator`，直连 Google `v1beta/models/{model}:streamGenerateContent?alt=sse` 与 `GET /models`，使用 `x-goog-api-key` 认证，原生发送 `inlineData` 识图、`generationConfig.thinkingConfig.includeThoughts` 流式思考摘要、`functionDeclarations`（无参工具省略 `parameters`）、并行 `functionResponse` 合并及多轮 `thoughtSignature` 往返；`LlmDialect.AUTO` 自动识别 `googleapis.com`（不含 `/openai`），并在模型编辑页提供「Gemini 原生」手动切换项。
- **Google OpenAI 兼容端点识图与工具调用修复**：清理 `ImageChatTools` 与 `ChatSearchTool` Schema 中 Google 拒绝的 `additionalProperties` / `minLength` / `maxLength` 等非标准字段；`ChatStreamAccumulator` 与 `ChatProtocol.assistant` 完整捕获并回传 `extra_content.google.thought_signature`，修复无 `index` 并行工具调用与重复 `id` 拼接；`ChatProtocol.request` 对 Google 端点出站自动剔除 `reasoning_content` / `reasoning` / `reasoning_details` 并为 `tool` 响应补齐函数名。
- **对话内 OpenAI 生图卡片确认流**：移除 `AppContainer` 中的临时拦截，打通 `ChatImageBatch.executeOpenAi`、`ImageChatTools.parseOpenAiPlan`、`ChatImagePlanCard` 与 `OpenAiImageRepository`，支持单图/多图确认卡片、GPT Image 参数预览与 USD 费用预估，确认后串行出图并自动保存至画廊与对话流。
- **「正在思考」动效与思考收起顺滑度**：将 `ChatReplyIndicator` 升级为与 `GenerationOrb` / `QueuedGenerationIndicator` 同族的柔光底晕 + 倾斜轨道 + 三颗连续正弦呼吸星点 + 扫光文字（同时修复生图阶段未匹配 `GenerationOrb` 的问题）；重构 `ChatScreen` 思考折叠区，将顶部间距移入 `AnimatedVisibility` 内部并采用同步缓出曲线与硬件裁剪层，消除收起末帧间距突跳顿挫。

## 构建与签名核对

- APK：`versionCode=46`、`versionName=0.1.46`；正式附件 `PocketNAI.apk`，13,468,782 字节。
- 实际编译源码：`5de4599684b45af6243810b6560e2912d016b646`。正式标签 `build-46` 固定到该实际编译提交；本发布核对文档提交不改变生产 APK 内容。
- 全部编译、JVM 单测（`31/31`）、Android 设备测试（`18/18`，总预算 `49/50`）、R8 资源收缩及 `apksigner` 正式签名均在持久 Instabox Android 14 沙箱（`android`）内完成；GitHub [校验上架任务 44704748743](https://github.com/Alahjarko/PocketNAI/actions/runs/44704748743) 采用 `publish_sha256` 预签名直传模式，跳过云端签名与 Gradle 构建，仅对沙箱已签 APK 执行 v2/v3 证书指纹校验、版本号/分享入口校验及无签名包非签名 ZIP 条目逐字节比对后直接发布至 `build-46`。
- 无签名包 SHA-256：`c2e04ad6577f64a890f97512b879297357745f18873d9509999aaacac77468f2`（13,406,507 字节）。
- 公开 APK SHA-256：`c92166418855915e936da0f48f102cb6cfb6914ecdb2efba7d7f733da939bd18`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与公开 `0.1.45` 完全一致。

已核对公开 Release 为 `Latest`、非草稿、非预发布，匿名下载公开 `PocketNAI.apk` 与沙箱内已签名包 SHA-256 完全一致；仅清理本次临时草稿，全部历史正式版本完整保留。沙箱内设备使用沙箱既有测试签名覆盖更新至 `0.1.46`，完整保留原有登录凭据与历史图片，全程未触发任何真实生成、超分或登录请求；沙箱实例已主动挂起，归档位于 `/data/home/workspace/artifacts/release-0.1.46/`。
