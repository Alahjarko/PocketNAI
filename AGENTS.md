# PocketNAI 开发约定

适用于本仓库的编码助手。先读本文件；修改某个功能前，必须按下表阅读对应专题，跨模块改动读全部相关专题。专题是开发约定的一部分，不要求每次加载全部文档。

## 按任务阅读

| 本次涉及 | 必须阅读 |
|---|---|
| 参考图、图生图、局部重绘、自定义尺寸、元数据导入、流式预览 | [图片与元数据](docs/development/images.md) |
| Room、历史记录、文件清理、草稿、参数持久化 | [数据与存储](docs/development/storage.md) |
| 提示词、权重、光标/选区、质量标签、独立角色 | [提示词与角色](docs/development/prompts.md) |
| 页面布局、生成悬浮层、详情、画廊筛选、收藏、分享 | [界面与交互](docs/development/ui.md) |
| HTTP、超时/重试、代理、凭据、登录、多账号 | [网络与认证](docs/development/network-auth.md) |
| 实验性对话、LLM 接入、思考回传、图片工具、人格 Markdown | [实验性对话](docs/development/chat.md)，并读网络、存储与界面专题 |
| 余额、订阅权益、费用报价、流水、高清放大 | [计费与超分](docs/development/billing.md) |
| 检查更新、APK 下载、签名、版本号、发布 | [更新与发布](docs/development/release.md) |
| 构建、安装、设备测试、协议核对 | [验证与待核对事项](docs/development/verification.md) |

常见跨模块任务：角色导入/详情读「图片 + 提示词 + 存储 + 界面」；生成链路读「图片 + 网络 + 存储 + 计费」；超分读「计费 + 网络 + 存储 + 界面」。

产品范围见 [规划书](docs/PocketNAI-规划书草案.md)，架构入口见 [架构说明](docs/PocketNAI-架构说明与功能拆解.md)，历史取舍与实测证据见 [技术决策记录](docs/PocketNAI-技术决策记录.md)。旧文档的实现描述可能落后于源码；遇到冲突先核对，不把旧实测当作当前服务端保证。

## 全局红线

1. **真实生成、超分及其他可能消耗 Anlas 的操作，只能由用户在界面手动触发。** 不代发真实登录，不读取 `persistent-api-token.txt`。即使本地报价为免费，也不构成自动生成授权；免费取决于当前订阅权益，历史账号读数不能当作永久属性。
2. **默认测试使用 MockWebServer 或假实现，不联网。** 既有只读例外是 `SocksProxyProbeTest`，只允许 GET NovelAI 首页验证代理建连，不涉及生成。用户明确授权的受控真实联调见下方对话授权边界；账号与费用细节见对应专题。
3. **秘密不得泄露。** Token、密码、Access Key、PST 不进入日志、普通数据库/首选项、图片元数据、剪贴板、异常或测试代码；凭据仅允许按既有 Keystore 加密方案保存。不要提交 `persistent-api-token.txt`、`local.properties` 或代理明文凭据。
4. **保留用户数据与签名兼容性。** 禁止破坏性迁移、重建 `generations` 表（DROP / RENAME）、更换既有签名密钥、为安装成功而卸载应用或清数据。设备测试前检查保留 APK 的设置，见交付流程。
5. **请求只发一次。** 生成零次自动重试；超时/断流报告 `TIMEOUT_UNCERTAIN`，流式失败不自动改发普通生成。代理切换仅限请求尚未发出的连接失败或幂等 GET/HEAD，已发出的 POST 不重发。
6. **日志只记结构。** 通用网络日志走 `RedactingHttpLogger`，仅方法、路径、状态码、耗时；Prompt 只记长度/哈希。禁止引入 `HttpLoggingInterceptor`。流式/代理诊断允许的结构字段见专题，不能追加内容或凭据。

对话功能的显式授权边界：用户主动开启“自动执行图片工具”并发送消息时，该次操作最多授权一次生成；默认仍通过生成卡片手动确认。真实联调测试必须有当前用户的明确授权，使用受控 `allowLive` 入口，范围和凭据清理见[对话专题](docs/development/chat.md)。其余自动生成及自动重试禁令继续适用。

## 修改与交付流程

**改完代码必须依次完成，不能停在“编译通过”或再问是否安装：**

1. 单元测试全绿：`./gradlew :app:testDebugUnitTest`。
2. 构建 APK：`./gradlew :app:assembleDebug`。
3. 直接覆盖安装：`adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/debug/app-debug.apk`；`emulator-5558` 在线时也安装。
4. 涉及界面时，用 `adb shell screencap` 截图确认实际显示，不以源码推断代替验收。

- Windows 可用 `gradlew.bat`；项目自带 `.tooling/gradle-8.11.1/bin/gradle.bat`，没有全局 Gradle 时使用它。
- Android SDK：`C:\Users\13911\AppData\Local\Android\Sdk`；MuMu 连接：`adb connect 127.0.0.1:5559`。
- 覆盖安装若被构建号拦下，先查询已安装版本，按发布专题的环境变量注入机制处理；不得卸载解决。
- 生成链路改动先跑 `GenerationSeedAndPayloadTest`（假 API、内存 Room、真实文件存储，不联网）；数据库升级补显式 Migration 和迁移测试，导出 schema。
- 跑设备测试前确认 `android.injected.androidTest.leaveApksInstalledAfterRun=true` 仍在；默认卸载会丢失凭据和历史。
- 纯文档修改只需检查内容、相对链接和差异，不要求构建/安装；报告中区分静态检查、设备验证与未验证事项。
- Git Bash 调用 adb 必须设 `MSYS_NO_PATHCONV=1`；截图、拖拽、文本输入和 WAL 排障技巧见[界面专题](docs/development/ui.md)。

## 架构与一致性

- `core/`、`domain/` 保持纯 Kotlin，不引入 Android API；纯逻辑用 JVM 单测，中文测试名、Truth 断言。依赖 Android 的位图、Room、Compose 验证才放设备测试。
- 首页画廊与生成面板共用 `PocketNaiApp` 层的 `GenerateViewModel`，不能下移到 Tab 或悬浮层内部。
- `GenerationDraft` 是可变草稿，`Generation` 是历史快照。新增生成参数时同步检查草稿 DTO、历史映射/存储、请求构造、导入与复用、详情展示，不允许某段链路静默丢字段。
- 随机 Seed 只通过 `withResolvedSeed` 抽一次，历史与请求共用结果；请求画布 `params.size` 与最终尺寸 `params.outputSize` 含义不同。
- 复用既有规则入口：提示词拼接 `PromptComposition.append`，权重 `EmphasisSyntax.strengthOf`，裁切 `ResolutionPlanner.centeredCrop`，订阅判断 `SubscriptionStatusResolver`，费用 `NovelAiPaidAnlasFormula`。
- NovelAI 请求统一走 `https://image.novelai.net`；更新检查与 APK 下载固定直连，不能套生成代理。
- 凭据兼容常量不得随意改动：`pocketnai_secure`、`pocketnai_token_key`、`token_iv`、`token_ciphertext`；备份排除项须与之同步。

## 定位代码

仓库根存在 `.codegraph/` 时，需要理解或定位代码，优先用 `codegraph_explore`（传本仓库路径）或 `codegraph explore "<符号或问题>"`，再按需搜索。目录不存在就跳过，不自行建立索引。

## 维护约定

- 根文件只放全局红线、交付流程和导航；功能参数、协议形态、UI 踩坑与历史证据放相应专题。
- 新增规则优先更新已有条目，避免同一规则维护多份全文。修改专题路径或标题时检查引用。
- 带日期的账号状态、服务端行为和兼容结论保留日期；需要当前事实时重新核对，不能为了核对自动花费 Anlas。
