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
- 默认人格只覆盖成年角色的非露骨创作；不能把明确色情描写作为已支持的功能。

## 思考与工具协议

- DeepSeek 使用 `thinking.type`；Kimi 可切换型号使用同一字段，固有思考型号不传不兼容开关。Kimi Code 使用 `reasoning_effort`。
- 不传 `temperature`，避免固定温度的思考模型拒绝请求。
- SSE 同时聚合正文、`reasoning_content`、`reasoning_details` 与分片 `tool_calls`。空的思考字段也保留，不能把“工具阶段没有思考文本”误判成未开启思考。
- 历史 assistant 消息连同思考与原始工具 ID 一起回传，tool 消息用 `tool_call_id` 与之配对。只有完整终止的回复才执行工具；截断、断流不执行半截参数。
- 只提供 `get_generation_settings` 与 `generate_image`，不暴露登录、令牌、文件删除、超分、参考图编码等能力。工具参数必须通过白名单与模型参数验证。

## 图片执行与数据

- 默认先显示参数和报价卡片，由用户点击“生成图片”。用户主动开启“自动执行图片工具”后，发送一次消息授权该条消息内最多执行一次图片生成。
- 上限在代码中计数；失败或超时同样用掉本条消息的预算，模型不能自行重试。对话最多 6 次 LLM 工具轮次，不自动续跑无限任务。
- `GenerateViewModel.generateForChat` 与首页共用 `inFlight`，复用原来的 `GenerationRepository`、Seed、图库与余额流水。对话参数独立快照，不覆盖首页草稿；不擅自附加参考图。
- 真实图片任务启动后不可取消。停止按钮只停止 LLM 回复或等待确认的任务。
- Room schema 9 只增加 `chat_conversations`；对话保存消息、思考、工具关系与图片索引，原图片表保持不变。删除对话只删对话；从图库删图后对话显示图片已移除。
- 进程重启只补齐中断工具的结果，不重新执行生成。用户需要发送新消息才能发起下一次操作。

## 长文与动画

- 正文超过 2000 字才默认折叠，不根据空行／列表行数判断。长文预览最多 1800 字，优先在 1200–1800 字之间的完整段落末尾停止；普通回复不截成 8 行。思考过程默认折叠。全文通过独立阅读器按最多 600 字逐段排版；提供复制全文与关闭入口，不使用长气泡高度动画，滑动不会误关闭阅读器。
- 流式增量全部聚合，但界面最多约每 120 ms 更新一次，最终完整回复必定提交。正在输出的正文最多排版最近 2400 字；折叠与投影不改变保存的完整正文、思考和工具 ID。
- 聊天键盘避让只由外层 Scaffold 负责，导航内容消费已应用的 Insets；IME 显示时隐藏底部导航。编辑器使用 `TextFieldValue` 保留光标、选区和中文组合输入，不把键盘高度与底部导航重复留空。
- 跟随滚动由单一协程顺序执行，不按 token 重启动画；用户拖动回看后暂停跟随，点击“回到最新”恢复。页面淡入 160 ms、淡出 100 ms。
- 历史 JSON 解析与保存走后台 IO，同一订阅缓存未变更的对话，避免保存一条消息后重复解析全部历史。上下文传输上限 512 KiB；超限要求新建对话，既有记录保留供阅读。单个对话 JSON 上限 1.5 MiB，避免 CursorWindow 超限损坏读取体验。
- 2026-10-07 精简后只保留本地组件的普通回复不误折叠检查；历史长文/帧率测试记录保留在下方，不再作为现有测试入口。
- 0.1.11 MuMu 验收：20 条消息、295034 字，最长 66892 字；真实滑动采样历史 273 帧、全文 737 帧，P95 分别 3.88 ms、2.11 ms，超过 32 ms 为 0。`ChatStreamingLayoutTest` 对生产气泡进行 40 次本地增量更新，正文从 5 万增至 10 万字，40 帧 P95 为 3.24 ms，超过 32 ms 为 0。该结果只代表此次模拟器环境，不是所有手机的性能保证；本地排版测试不替代真实服务验收。

## 验证记录

- 纯协议、分段重组、参数验证使用 JVM 测试；数据库升级用 `MigrationTest.migrate8To9AddsChatAndKeepsImageHistory`。
- 自动化测试不联网。真实联调仅在当前会话获得用户明确授权后进行，使用临时凭据并在结束后清理和恢复原配置；旧 `LiveChatAcceptanceTest` 入口已删除。
- 本次用户已明确授权使用提供的限额 LLM Key 进行普通聊天，以及模拟器已登录账号的 V4.5 单张 SFW 生图验收。授权不扩展到 NSFW 测试、其他付费工具或未来自动运行。
- 真实生图验收使用单次 marker 防止重复发出请求；出现不确定结果先读回图库／记录，不清 marker 后自动重发。
- 本次真实服务结果：DeepSeek `deepseek-flash` 与 Kimi Code `kimi-for-coding` 普通聊天、思考返回、只读工具与思考回传均通过；V4.5 Curated 一张 832×1216 的 SFW 图片完成真实生成，在聊天、图片时钟入口与原图库中读回。临时凭据删除并恢复原配置。后续长文优化未重复生图。
- 0.1.12 附件验证使用已有 SFW 图、相册 URI、真实私有文件与内存 Room，完成附件发送和重读；HTTP 格式／拒绝路径由本机 MockWebServer 验证，不声称本轮已进行真实服务识图联调。`ChatComposerInteractionTest` 的辅助 IME 仅在测试 APK 中，用真实 Android IME 窗口与 InputConnection 验证避让和中文组合输入，结束后恢复原输入法；不会进入发布包。
- 0.1.12（2026-10-05）：629 项 JVM 测试与 10 项设备测试通过，包含附件持久化、中文组合输入／光标／换行／删除、实际 IME 高度、软件图库选图／移除、短列表不折叠、长文／流式排版与生成链路回归。扩大预览后的最终样本为历史 270 帧 P95 4.07 ms、全文 734 帧 P95 2.16 ms、增量排版 40 帧 P95 3.41 ms，超过 32 ms 均为 0；边界同上。

官方协议参考：[DeepSeek 思考与工具回传](https://api-docs.deepseek.com/guides/thinking_mode/)、[Kimi 思考模型](https://platform.kimi.ai/docs/guide/use-thinking-models)、[Kimi Code 模型配置](https://www.kimi.com/code/docs/en/kimi-code/models.html)、[OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)。以当前服务实际返回为准。
