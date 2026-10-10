# 实验性对话与图片工具

## 入口与配置

- 设置页“实验性对话”默认关闭。开启后，在窄屏底部栏、宽屏侧栏增加“对话”；关闭不删除记录。
- 连接配置支持 HTTPS 的 OpenAI-compatible Chat Completions。Base URL 的路径前缀原样保留，在其后追加 `/models` 或 `/chat/completions`，不猜测追加 `/v1`。
- 模型列表由用户点击获取，也允许手动填写模型 ID。思考默认开启；自动协议根据模型名／Base URL 判断，也可手动选择 Kimi、DeepSeek、OpenAI 兼容。
- API Key 不属于 `LlmConfig`，仅在 `pocketnai_llm.xml` 中以独立 Keystore alias `pocketnai_llm_secret_key` 加密保存；该首选项在两套备份规则中排除。输入框不得使用 `rememberSaveable`，也不得回显已保存的密钥。
- LLM 独立客户端不走 NovelAI 公益代理，无自动重试、无正文日志、无自动重定向；错误只显示固定文案和 HTTP 状态。
- 已保存 Key 绑定对应 Base URL；更换地址必须填写新 Key。阻止把当前 Key 当消息／人格发送，并移除服务端误回显到正文、思考、工具参数中的当前认证值。

## 图片附件（2026-10-05）

- 输入框加号只负责添加图片，提供“从相册选择”“从软件画廊选择”；连接与人格保留在顶部菜单。附件显示可预览、可移除的缩略图，每条消息最多 4 张，也支持只发图片。
- 从图库选图复制为独立聊天附件，不修改原生成图。相册走系统 Photo Picker 的 URI 授权，不申请全图库访问权限。导入按 EXIF 旋转／镜像、最长边不超过 1600，统一 JPEG 并清掉元数据；单张上限 512 KiB，原图读取上限 20 MiB。
- 私有文件路径 `files/chat-attachments/<uuid>.jpg`，`ChatEntry.attachments` 仅保存索引、尺寸与 MIME；旧条目默认空附件，无需改变 Room schema。取消／移除待发送图片只删除该附件副本；删除对话时先核对其他对话与草稿的存活引用，再删除不再使用的附件。
- 发送时才将主动添加的附件编码成 `content` 数组中的 `image_url` data URL，与 text 部分一起发至当前 LLM。base64 不写 Room、草稿或日志；未主动添加的生成图片不会发送给 LLM，附件不会自动作为 NovelAI 参考图。图片上下文原始 JPEG 总量上限 8 MiB。
- 模型必须支持视觉输入；供应商拒绝图片时提示检查识图能力及参数，不悄悄丢图或自动重试。参考：[Kimi 图片消息格式](https://platform.kimi.ai/docs/guide/use-kimi-vision-model)、[DeepSeek 视觉输入](https://api-docs.deepseek.com/guides/vision/)。

## 可编辑人格文件

- 默认入口是 `app/src/main/assets/chat/soul.md` 与 `tools.md`，分别管理人格／回复风格与工具使用说明。
- 手机上的自定义覆盖在私有目录 `files/chat-agent/`，通过“连接与人格”编辑，也可以使用系统文档选择器导入／导出 `.md`。单个文件上限 128 KiB。
- 每次发送时读取一份人格快照；保存后的修改在下一次发送生效。修改 Markdown 不会新增工具权限、允许任意文件访问或执行任意 API。
- 默认人格不预设内容立场（2026-10-10 起）：人物须明确成年，工具不拦截提示词题材；能否生成由 NovelAI 服务端与账号策略决定，成人内容属于用户与 NovelAI 之间的事。仅保留一条底线：工具拒绝涉及未成年人的性内容（NovelAI 服务条款同样禁止）。

## 思考与工具协议

- DeepSeek 使用 `thinking.type`；Kimi 可切换型号使用同一字段，固有思考型号不传不兼容开关。Kimi Code 使用 `reasoning_effort`。
- 不传 `temperature`，避免固定温度的思考模型拒绝请求。
- SSE 同时聚合正文、`reasoning_content`、`reasoning_details` 与分片 `tool_calls`。空的思考字段也保留，不能把“工具阶段没有思考文本”误判成未开启思考。
- 历史 assistant 消息连同思考与原始工具 ID 一起回传，tool 消息用 `tool_call_id` 与之配对。只有完整终止的回复才执行工具；截断、断流不执行半截参数。
- 只提供 `get_generation_settings` 与 `generate_image`，不暴露登录、令牌、文件删除、超分、参考图编码等能力。工具参数必须通过白名单与模型参数验证。

## 图片执行与数据

- 默认先显示参数和报价卡片，由用户点击“生成图片”。用户主动开启“自动执行图片工具”后，发送一次消息授权该条消息内最多执行一次图片生成。
- 上限在代码中计数；失败或超时同样用掉本条消息的预算，模型不能自行重试。对话最多 6 次 LLM 工具轮次，不自动续跑无限任务。
- 角色工具支持use_coords；省略坐标默认AI安排，明确传坐标默认手动，数量按模型6/32限制。质量词、负向预设、宏与旧随机语法走统一生成仓库。
- `GenerateViewModel.generateForChat` 与首页共用 `inFlight`，复用原来的 `GenerationRepository`、Seed、图库与余额流水。对话参数独立快照，不覆盖首页草稿；不擅自附加参考图。
- 真实图片任务启动后不可取消。停止按钮只停止 LLM 回复或等待确认的任务。
- Room schema 9 只增加 `chat_conversations`；对话保存消息、思考、工具关系与图片索引，原图片表保持不变。删除对话只删对话；从图库删图后对话显示图片已移除。
- 进程重启只补齐中断工具的结果，不重新执行生成。用户需要发送新消息才能发起下一次操作。

## 长文与动画

- 正文超过 2000 字才默认折叠，不根据空行／列表行数判断。长文预览最多 1800 字，优先在 1200–1800 字之间的完整段落末尾停止；普通回复不截成 8 行。思考过程默认折叠。流式长文也保留开头预览，提供“查看已收到内容”的阅读快照；完成后通过“查看全文”打开阅读器。折叠、显示投影和阅读分页不改变原始正文、思考或复制内容。
- Markdown 使用 Apache-2.0 的 `mikepenz/multiplatform-markdown-renderer-m3:0.33.0` 原生 Compose 渲染。段落解析在后台完成，已完成段落保留稳定槽位，变化中的末段解析期间沿用上次结果；最多缓存24份解析结果，只在进程内存中保存。流式显示暂时隐藏未闭合的行内加粗／代码标记，结束后提交原文。阅读器懒加载分页，长代码块分页补齐语言与围栏上下文；超长表格等跨页结构不保证与整篇一次排版完全相同。不自动加载远程 Markdown 图片，链接仅在用户点击时打开 http/https。
- 网络流全部聚合，约每120ms提交显示快照；正文再按积压量逐帧分批显示。页面不产生帧时暂停显示推进，原始流继续接收。流式状态只由当前回复的懒加载条目订阅，顶部、输入区与历史列表不随每个正文片段重新订阅；消息列表使用稳定key与contentType。最终消息和临时回复在同一次状态更新中交接，磁盘保存放在其后。
- 停止、断流或错误仍提交最后已收到的显示快照，并保存正文／思考和中断说明；中断条目移除所有工具调用，不把半截参数作为后续可执行工具。完整终止校验、图片工具预算和零自动重试保持有效。
- `ChatComposer` 使用原生 `BasicTextField`，保留 `TextFieldValue` 的选区和组合输入；输入区最多6行，附件与发送／停止放在下方工具行。发送后主动收起输入法（2026-10-10 用户要求，首页生成按钮同理），停止按钮不动键盘。设备具有系统语音识别Activity时才显示语音按钮，识别结果插入当前选区；未提供服务时不显示，不新增应用录音权限。
- 聊天键盘避让由页面框架统一负责：底栏始终保留布局高度，内容先消费底栏边距，再只补足 IME 的剩余高度，总避让量为两者的较大值；底栏按键盘实际高度裁切绘制。不能按 `isImeVisible` 移除/重建底栏，否则收起键盘时输入框会先下沉再被顶起。编辑器继续使用 `TextFieldValue` 保留光标、选区和中文组合输入。
- 聊天头部与整个输入区域（含圆角外侧空隙）通过 `ChatPageLayout` 注册为共享图片动画的前景。消息列表保持裁切，返回详情时图片位于输入区下层；不能只给圆角输入框提层，留下周围空隙。“回到最新”是消息区右下角的40dp圆形悬浮箭头，独立注册共享动画前景，出现／消失不占额外行、不改变列表或输入框高度，保留无障碍描述。
- 跟随滚动监听实际列表布局，由单一协程顺序执行，不轮询、不按 token 重启动画；用户拖动回看后暂停跟随，点击悬浮箭头恢复。状态提示使用实际阶段文字与三点透明度动画，不虚构服务端百分比。页面淡入160ms、淡出100ms，悬浮箭头淡入140ms、淡出100ms并轻微缩放。
- 历史 JSON 解析与保存走后台 IO，同一订阅缓存未变更的对话，避免保存一条消息后重复解析全部历史。上下文传输上限 512 KiB；超限要求新建对话，既有记录保留供阅读。单个对话 JSON 上限 1.5 MiB，避免 CursorWindow 超限损坏读取体验。
- 2026-10-10 扩展既有聊天设备测试，涵盖原生Markdown、长文流式回放、悬浮箭头不挤动布局、图片返回遮挡及真实测试IME组合输入；未增加测试方法。历史长文/帧率测试记录保留在下方，不代表当前渲染方案的性能。
- 0.1.11 MuMu 验收：20 条消息、295034 字，最长 66892 字；真实滑动采样历史 273 帧、全文 737 帧，P95 分别 3.88 ms、2.11 ms，超过 32 ms 为 0。`ChatStreamingLayoutTest` 对生产气泡进行 40 次本地增量更新，正文从 5 万增至 10 万字，40 帧 P95 为 3.24 ms，超过 32 ms 为 0。该结果只代表此次模拟器环境，不是所有手机的性能保证；本地排版测试不替代真实服务验收。

## 验证记录

- 2026-10-10 对话界面重做：32项JVM与18项设备测试全绿，仍为50项。新增行为扩展已有测试，HTTP使用本机MockWebServer验证截断流保留末段但不执行工具。优化包使用原签名覆盖安装，已有聊天中截图确认悬浮箭头与正文、输入区的实际位置；本轮未发送真实LLM消息或生图。语音入口仅完成可用性判断与构建检查，MuMu没有系统识别服务，未验收真实语音转写。
- 选型核对：Stream AI仓库当前根[许可证](https://github.com/GetStream/stream-chat-android-ai/blob/develop/LICENSE)带有客户与使用限制，不按MIT引入。采用的Markdown库[许可证为Apache-2.0](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/develop/LICENSE)，[0.33.0的MarkdownState](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.33.0/multiplatform-markdown-renderer/src/commonMain/kotlin/com/mikepenz/markdown/model/MarkdownState.kt)支持后台解析；项目未升级Kotlin、AGP或compileSdk配置。该版本不是完整的增量语法树引擎，当前优化来自应用侧段落稳定性与显示节流。
- 纯协议、分段重组、参数验证使用 JVM 测试；数据库升级用 `MigrationTest.migrate8To9AddsChatAndKeepsImageHistory`。
- 自动化测试不联网。真实联调仅在当前会话获得用户明确授权后进行，使用临时凭据并在结束后清理和恢复原配置；旧 `LiveChatAcceptanceTest` 入口已删除。
- 本次用户已明确授权使用提供的限额 LLM Key 进行普通聊天，以及模拟器已登录账号的 V4.5 单张 SFW 生图验收。授权不扩展到 NSFW 测试、其他付费工具或未来自动运行。
- 真实生图验收使用单次 marker 防止重复发出请求；出现不确定结果先读回图库／记录，不清 marker 后自动重发。
- 本次真实服务结果：DeepSeek `deepseek-flash` 与 Kimi Code `kimi-for-coding` 普通聊天、思考返回、只读工具与思考回传均通过；V4.5 Curated 一张 832×1216 的 SFW 图片完成真实生成，在聊天、图片时钟入口与原图库中读回。临时凭据删除并恢复原配置。后续长文优化未重复生图。
- 0.1.12 附件验证使用已有 SFW 图、相册 URI、真实私有文件与内存 Room，完成附件发送和重读；HTTP 格式／拒绝路径由本机 MockWebServer 验证，不声称本轮已进行真实服务识图联调。`ChatComposerInteractionTest` 的辅助 IME 仅在测试 APK 中，用真实 Android IME 窗口与 InputConnection 验证避让和中文组合输入，结束后恢复原输入法；不会进入发布包。
- 0.1.12（2026-10-05）：629 项 JVM 测试与 10 项设备测试通过，包含附件持久化、中文组合输入／光标／换行／删除、实际 IME 高度、软件图库选图／移除、短列表不折叠、长文／流式排版与生成链路回归。扩大预览后的最终样本为历史 270 帧 P95 4.07 ms、全文 734 帧 P95 2.16 ms、增量排版 40 帧 P95 3.41 ms，超过 32 ms 均为 0；边界同上。

官方协议参考：[DeepSeek 思考与工具回传](https://api-docs.deepseek.com/guides/thinking_mode/)、[Kimi 思考模型](https://platform.kimi.ai/docs/guide/use-thinking-models)、[Kimi Code 模型配置](https://www.kimi.com/code/docs/en/kimi-code/models.html)、[OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)。以当前服务实际返回为准。
