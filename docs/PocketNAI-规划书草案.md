# PocketNAI Android 应用规划书（讨论草案）

> 文档状态：首版范围基线 v0.1（开发进展见 README 与技术决策记录）
>
> 更新日期：2026-09-13
>
> 当前目标：先明确产品范围、用户流程和 NovelAI API 边界；技术选型、界面原型、数据结构细节和开发排期将在后续讨论中继续补齐。

## 1. 产品定义

PocketNAI 是一个面向 Android 的 NovelAI 图像生成客户端。首版专注于手机端更便捷的文字生成图片（Text to Image，T2I）体验，使用用户自己的 NovelAI Persistent API Token 直接连接 NovelAI，不要求用户向应用提供 NovelAI 邮箱或密码。

首版的核心价值：

- 在手机上完成从提示词输入、参数设置、生成预览到历史管理的完整闭环。
- 支持 NovelAI V4.5 与 V5 的 Curated、Full 四个模型。
- 将生成结果保存在应用本地，使用瀑布流浏览。
- 将令牌、提示词和生成历史限制在设备本地，不引入不必要的中转服务器。
- 优先保证生成请求可靠、结果不丢失、错误信息明确，不追求一次复刻网页版全部高级功能。

## 2. 首版范围

### 2.1 纳入首版的第一层能力

- Persistent API Token 导入、验证、替换和删除。
- Token 的 Android Keystore 保护及所有日志脱敏。
- 四个指定图像模型：
  - `nai-diffusion-4-5-curated`
  - `nai-diffusion-4-5-full`
  - `nai-diffusion-5-curated`
  - `nai-diffusion-5-full`
- T2I 文字生成图片。
- 正向提示词。
- Undesired Content（负面提示词）。
- 模型选择。
- 图片尺寸、横竖方向和生成张数。
- Steps。
- Prompt Guidance。
- CFG Rescale。
- Sampler。
- Noise Schedule。
- 随机 Seed 与固定 Seed。
- Quality Tags 开关。
- 只生成 PNG。
- 生成中状态、成功状态和失败提示。
- 结果写入应用私有数据目录。
- 本地历史数据库与瀑布流画廊。
- 长按图片后：保存到系统相册、删除本地记录、复制提示词。
- 从历史记录重新载入 Prompt、Seed、模型和参数。

### 2.2 纳入首版的第二层能力

- 生成过程中的流式中间预览。
- NovelAI 标签建议和自动补全。
- 提示词强化与弱化语法的便捷编辑。
- Prompt Randomizer。
- 本地 Prompt Chunks / 提示词片段。
- 本地参数预设。
- PNG 元数据读取和参数恢复。
- 分享、复制及导出图片。

### 2.3 明确不纳入首版

- Img2Img。
- Inpaint 和蒙版编辑器。
- Enhance。
- Upscale。
- 多角色 Prompt 和角色位置控制。
- Vibe Transfer 与 Vibe 编码缓存。
- Precise Reference。
- Director Tools。
- Canvas、画笔、橡皮、图层和复杂撤销栈。
- 文字渲染专用 Prompt 转换。
- 透明背景。
- Anlas 费用计算和费用预估。
- NovelAI 故事、文本生成、Lorebook 或故事同步。
- 无人值守自动生成、后台循环生成或自动刷图。

这些能力不是永久取消，而是从首版范围和首版验证清单中移除；后续应根据实际使用反馈单独规划。

## 3. 模型与提示词策略

### 3.1 统一界面

四个模型共用同一套生成页面和主要交互，不为 V4.5、V5 设计两套完全不同的 UI。模型差异由内部 `ModelProfile` 处理。

`ModelProfile` 至少包含：

- API 模型 ID。
- 显示名称。
- 默认尺寸。
- 默认 Steps、Guidance、CFG Rescale。
- 可用 Sampler 列表。
- 每个 Sampler 可用的 Noise Schedule。
- Quality Tags 规则。
- Undesired Content 预设规则。
- 提示词长度提示。
- 是否支持多语言提示词。
- 协议兼容标志。

界面只能展示当前模型实际允许的 Sampler 和 Noise Schedule 组合，避免用户构造已知无效请求。

### 3.2 默认参数原则

不在规划阶段凭经验猜一组永久默认值。进入实现阶段时，从 NovelAI 当前网页版分别记录四个模型的新建任务默认值，并固化为带版本号的模型配置。

默认值的更新原则：

- 以 NovelAI 当前网页版为准。
- 四个模型可以有不同内部默认值，但不造成不同的操作流程。
- 应用升级不得静默修改已经保存的历史任务参数。
- 新默认值只影响新建任务和用户主动执行“恢复模型默认值”。
- 参数模型保留未知字段的兼容空间，方便 NovelAI 更新接口后迁移。

### 3.3 V4.5 与 V5 的结构化 Prompt

首版虽然不做多角色，但网络层仍应正确构造 V4.5/V5 所需的结构化 Prompt：

- `v4_prompt.caption.base_caption` 保存处理后的正向提示词。
- `v4_prompt.caption.char_captions` 为空数组。
- `v4_negative_prompt.caption.base_caption` 保存处理后的负向提示词。
- `v4_negative_prompt.caption.char_captions` 为空数组。
- 不启用自定义角色坐标。

该结构只存在于 API 适配层，用户仍只看到普通正向和负向提示词输入框。

### 3.4 多语言

- V5 允许用户直接输入受其支持的多语言提示词。
- 首版不自动翻译，也不在提交前擅自改写用户 Prompt。
- V4.5 仍允许输入任意文本，但界面可用非阻断式说明提示其多语言理解能力弱于 V5。
- 首版不做网页版的文字渲染 Prompt 自动抽取和 `teXt:` 转换。

## 4. 核心用户流程

### 4.1 首次连接

1. 用户在 NovelAI 官方网页生成 Persistent API Token。
2. 用户在 PocketNAI 中粘贴 Token。
3. PocketNAI 使用只读账户状态请求验证 Token。
4. 验证成功后，将 Token 使用 Android Keystore 支持的方案加密保存。
5. 界面仅显示已连接状态和脱敏标识，不再完整显示 Token。
6. 验证失败时弹出明确提示，不保存无效 Token。

PocketNAI 不实现邮箱和密码登录，也不保存 NovelAI 密码或派生登录密钥。

### 4.2 创建生成任务

1. 用户进入生成页。
2. 输入正向提示词。
3. 可选输入 Undesired Content。
4. 选择模型、尺寸、张数和参数。
5. 点击“生成”。
6. 应用在本地立即创建一个 `Generating` 状态的任务记录和占位卡片。
7. 请求发出后，生成不可取消；离开页面不等同于取消服务端任务。
8. 若流式预览可用，占位卡片持续显示中间图和进度。
9. 收到最终 PNG 后，原子写入应用私有目录，更新数据库并替换占位卡片。
10. 若失败，将任务更新为 `Failed` 并弹出可理解的错误提示。

### 4.3 历史瀑布流

- 首页或独立历史页使用双列或自适应列数的瀑布流。
- 每张最终图片是一张可独立操作的卡片。
- 同一次请求生成的多张图片共享一个 Generation ID，但各自拥有独立 Image ID。
- 卡片显示图片；时间、模型、尺寸等信息默认保持简洁，可在详情页查看。
- 点击图片进入详情页。
- 长按图片进入操作菜单：
  - 保存到系统相册；
  - 删除本地记录；
  - 复制正向提示词。
- 详情页提供“复用参数”，将正向提示词、负向提示词、Seed、模型、尺寸和采样参数载入生成页，但不会自动开始生成。

### 4.4 保存与删除语义

- 生成成功后，图片先保存在应用私有目录，作为 PocketNAI 历史的一部分。
- “保存”是复制到 Android 系统相册，而不是把文件从应用目录移走。
- 删除 PocketNAI 历史只删除应用私有副本和对应数据库记录。
- 用户已经保存到系统相册的副本不随 PocketNAI 历史删除。
- 删除操作应提供短时间撤销机会；撤销期间先标记删除，确认后再清理文件。

## 5. 本地文件与命名

### 5.1 内部目录

应用内部采用稳定、无碰撞、与用户输入解耦的目录结构：

```text
generations/<generation-id>/
  0001.png
  0002.png
  0003.png
  0004.png
```

不直接用完整 Prompt 作为内部目录或文件名，以避免：

- 文件名过长；
- 非法路径字符；
- 同名覆盖；
- Emoji 和不同 Unicode 规范化导致的兼容问题；
- Prompt 意外出现在系统日志、崩溃路径或备份诊断信息中。

### 5.2 用户可见名称

- 每次生成记录的默认标题从正向提示词提取。
- 提取时去掉多余空白和换行，并截取一个适合列表显示的长度。
- Prompt 为空或无法形成标题时，使用“未命名生成 + 时间”。
- 同批图片按生成顺序编号，从 `01` 开始。
- 保存到系统相册时使用经过清理的标题、时间和顺序号，例如：

```text
silver-haired-girl_20260913-223015_01.png
silver-haired-girl_20260913-223015_02.png
```

中文 Prompt 可以保留中文标题；不支持的文件名字符统一替换，不覆盖已有文件。

## 6. API 与传输设计

### 6.1 API 基线

- Base URL：`https://image.novelai.net`
- 鉴权：`Authorization: Bearer <Persistent API Token>`
- T2I 主请求：`POST /ai/generate-image`
- 流式预览：由独立 `StreamingGenerationTransport` 负责。
- 标签建议：`GET /ai/generate-image/suggest-tags`
- 输出格式固定为 PNG。
- 每次生成必须由用户明确点击触发。

### 6.2 ZIP 只作为网络传输容器

PocketNAI 不在历史中保存 ZIP，也不向用户展示 ZIP。

普通最终结果建议使用 NovelAI 的 ZIP 响应，理由是：

- 避免 Base64 约三分之一的体积膨胀；
- 避免将整批 Base64 字符串和解码后图片同时放入内存；
- 可以将响应流写入受控临时文件，再逐个解出 PNG；
- 更适合多图和高分辨率响应。

处理流程：

1. 将 ZIP 响应流写入任务专用临时文件。
2. 验证每个 ZIP entry 的规范化路径仍位于任务临时目录内。
3. 只接受符合预期数量和大小限制的图片 entry。
4. 检查文件签名，确认结果为 PNG，而不是只信扩展名。
5. 按 ZIP entry 顺序映射为 `0001.png`、`0002.png` 等内部文件名。
6. 使用临时文件加原子重命名写入最终目录。
7. 数据库提交成功后删除 ZIP 临时文件。
8. 任一步骤失败时清理未完成文件，但保留失败任务记录供用户查看。

### 6.3 流式预览协议

NovelAI 的公开 OpenAPI 描述了 SSE 流式生成端点，但当前官方网页版还存在 MessagePack 流式实现。为避免协议变化影响整个应用，流式解析必须隔离为可替换模块：

```text
GenerationRepository
  ├── FinalGenerationTransport
  └── StreamingGenerationTransport
        ├── SSE parser
        └── MessagePack parser（仅在验证需要后启用）
```

统一向上层输出：

```text
Started
Intermediate(imageIndex, imageBytes, progress?)
Final(imageIndex, imageBytes, seed, metadata?)
ItemError(imageIndex?, code, message)
Completed
FatalError(code, message, correlationId?)
```

流式规则：

- 中间图片只用于界面预览，不写入正式历史目录。
- 只有 Final 图片进入最终历史。
- 新的 Intermediate 到达时可覆盖上一张中间预览，限制内存占用。
- 流式请求失败后不自动发起第二次生成。
- 若服务器已经接收任务但连接中断，提示“结果状态不确定”，由用户决定是否再次生成。
- 记录流式协议失败次数；连续失败达到阈值后，只关闭未来任务的流式预览，并提示用户当前将使用普通生成。
- 关闭流式预览不重试刚刚失败的付费任务。
- 设置页允许用户重新启用流式预览。

开发开始前必须完成一个不保存 Token 的协议探针，确认：

- SSE endpoint 的实际请求头和请求体。
- event 名称及 `data` 编码。
- Intermediate、Final、Error、Completed 的顺序。
- 多图请求中的图片序号。
- 进度值的范围和含义。
- 最终事件是否包含 Seed 和完整参数。
- 断流、服务端错误和单张失败的表现。
- V4.5 与 V5 是否使用相同流式协议。

探针输出只能包含脱敏后的字段结构、类型、事件顺序和状态码，不得记录 Authorization、完整 Prompt 或图片内容。

## 7. 历史数据模型（概念层）

### 7.1 Generation

- `id`
- `createdAt`
- `updatedAt`
- `status`: `Generating | Succeeded | Partial | Failed`
- `title`
- `prompt`
- `negativePrompt`
- `modelId`
- `width`
- `height`
- `sampleCount`
- `steps`
- `guidance`
- `cfgRescale`
- `sampler`
- `noiseSchedule`
- `seedMode`
- `baseSeed`
- `qualityTagsEnabled`
- `requestSnapshotVersion`
- `errorCode`
- `errorMessage`
- `correlationId`

### 7.2 GeneratedImage

- `id`
- `generationId`
- `ordinal`
- `seed`
- `privateFilePath`
- `width`
- `height`
- `byteSize`
- `sha256`
- `metadataJson`
- `createdAt`
- `exportedUri`

数据库不保存 PNG 二进制，只保存文件索引和生成参数。图片文件与数据库记录的提交需要有恢复机制，应用启动时应能够清理孤立临时文件并识别丢失文件。

## 8. Prompt 工具

### 8.1 标签建议

- 在用户停止输入一个短暂间隔后请求标签建议。
- 新输入会取消旧建议请求。
- 标签建议失败不阻止正常生成。
- 不记录用户每次输入的搜索请求。
- 支持接口允许的语言参数；界面语言和 Prompt 语言不强绑定。

### 8.2 强化与弱化

- 首版提供快捷按钮帮助用户包裹或调整 NovelAI 支持的强调语法。
- 不在后台擅自重写完整 Prompt。
- 所有自动插入都必须立即在编辑框中可见并可撤销。

### 8.3 Prompt Randomizer

- 在本地解析随机选项。
- 每次点击生成时先固定本次展开结果，再将展开后的 Prompt 作为请求快照保存。
- 历史同时保存原始模板和本次实际 Prompt，确保“复制提示词”和“复现生成”语义明确。

### 8.4 Prompt Chunks 与参数预设

- 首版只存本地，不访问 NovelAI 的故事或用户数据同步接口。
- Prompt Chunk 支持名称、内容、分组、搜索和插入。
- 参数预设保存模型及可复用参数，但在切换模型时执行兼容性校验。
- 如果预设中的 Sampler 或 Noise Schedule 不适用于目标模型，界面要求用户选择新的有效组合，而不是静默替换后立即生成。

## 9. 错误处理

所有用户可处理或需要知晓的错误使用弹窗、对话框或明确的页面状态提示。历史中的失败任务保留简短错误摘要。

### 9.1 错误映射

| 情况 | 用户提示与处理 |
|---|---|
| Token 无效或失效 | 提示重新连接 NovelAI；不尝试邮箱密码登录 |
| 余额不足 | 提示可用额度不足；保留当前 Prompt 和参数 |
| 模型或订阅不可用 | 提示当前账户不能使用该模型；返回参数页 |
| 参数无效 | 标出相关参数；不自动用另一组参数重新提交 |
| 请求过于频繁 | 提示稍后再试；不自动后台重试 |
| NovelAI 服务端错误 | 显示简洁信息和脱敏 correlation ID |
| 网络不可用 | 提示检查网络；保留编辑内容 |
| 请求超时或断流 | 提示服务端可能已接受任务，禁止自动重试 |
| 只收到部分图片 | 保存已验证的最终 PNG，并将任务标记为 `Partial` |
| PNG/ZIP 校验失败 | 不展示损坏文件；保留失败信息并清理临时数据 |
| 本地存储空间不足 | 停止落盘，提示释放空间；不得删除既有历史 |
| 保存到相册失败 | PocketNAI 私有历史保持不变，提示用户重试授权或保存 |

### 9.2 重试原则

- T2I POST 请求默认零次自动重试。
- 标签建议等只读请求可进行有限重试。
- 只有在确认请求尚未发送到服务端时，才能安全地自动重新建立连接。
- 对超时、断流和 5xx，不自动重新生成图片。
- 用户手动重试前展示“上一任务可能已经产生消耗”的说明。

## 10. 隐私与安全

- Token 只保存在设备本地的受保护存储中。
- 禁止把 Token 写入普通偏好设置、数据库、图片元数据、剪贴板历史、分析事件或崩溃报告。
- Release 构建禁用包含请求头或请求正文的网络日志。
- Debug 构建也必须默认脱敏 Authorization。
- 日志中 Prompt 默认记录长度和哈希，不记录原文；问题复现由用户主动选择导出脱敏诊断。
- 应用不建立开发者中转服务器。
- 应用不自动上传本地历史、图片或 Prompt。
- Android 系统备份策略需要显式排除 Token，并由后续技术设计决定是否排除私有历史图片。
- 复制提示词时只复制用户选择的 Prompt，不复制 Token 或隐藏请求字段。

## 11. 发布与验收边界

首版完成不能只以“请求成功一次”为标准。至少需要在真实 Android 设备上验证：

- 四个模型各完成一次 T2I。
- 四个模型的默认参数和有效参数组合。
- V5 多语言 Prompt。
- 单张和多张生成。
- 横图、竖图和方图。
- 固定 Seed 复用。
- 普通 ZIP 返回的逐图保存。
- 流式中间预览和最终图替换。
- 流式失败后的非重复生成策略。
- 应用切到后台再返回后的任务状态。
- 网络中断、超时、401、额度不足、参数错误和 5xx 提示。
- 应用进程被系统回收后的临时文件恢复。
- 瀑布流滚动时的内存与帧率。
- 长按保存、删除、复制提示词。
- 保存到系统相册后删除 PocketNAI 私有记录，系统相册副本仍存在。
- PNG 元数据解析与参数恢复。
- Token 不出现在日志、数据库、导出文件和崩溃信息中。

## 12. 后续仍需讨论的产品决策

以下问题不阻碍当前范围成立，但需要在最终规划书前继续确定：

1. PocketNAI 是个人自用、公开 APK、开源项目还是计划上架应用商店。
2. Android 最低版本和目标设备范围。
3. 是否使用 Jetpack Compose，以及整体视觉方向。
4. 生成页采用“简洁模式 + 高级抽屉”，还是完整参数单页。
5. 瀑布流位于首页还是生成页旁边的独立 Tab。
6. 应用退到后台时，是否使用前台服务维持生成连接并显示通知。
7. 私有历史的缓存上限、自动清理规则和手动清理入口。
8. 删除操作采用立即删除加撤销，还是删除前确认。
9. NSFW 内容在本地画廊、通知预览和系统最近任务缩略图中的隐私策略。
10. Prompt Chunk 和参数预设的导入导出格式。
11. 是否需要深色主题、多语言 UI 和动态颜色。
12. 是否允许用户关闭流式预览以降低网络和电量消耗。

## 13. 当前决策记录

| 决策 | 当前结论 |
|---|---|
| 首版能力层级 | 第一层 + 第二层 |
| 生成模式 | 只做 T2I |
| 模型 | 指定四个 V4.5/V5 Curated/Full 模型 |
| 模型 UI | 不做两套不同操作界面 |
| V5 Prompt | 支持用户直接输入多语言 Prompt |
| 文字渲染转换 | 暂不做 |
| 生成取消 | 不提供服务端取消；发出后视为不可取消 |
| 本地历史 | 应用私有目录 + 数据库 + 瀑布流 |
| 图片格式 | 只生成 PNG |
| 网络返回 | 建议 ZIP 传输后直接解为独立 PNG，不保留 ZIP |
| 图片内部编号 | 同批按顺序编号 |
| 用户可见标题 | 从正向 Prompt 提取 |
| 长按操作 | 保存、删除、复制提示词 |
| Img2Img 等高级能力 | 首版不做 |
| Vibe / Precise Reference / Director | 首版不做，不再作为首版阻塞项 |
| Anlas 费用 | 首版暂不计算 |
| 错误处理 | 弹出明确提示，并保留可恢复的编辑内容 |
| 自动重试 | 生成请求不自动重试 |

## 14. 当前官方依据

本草案涉及 NovelAI 当前行为的部分，以 2026-09-13 可访问的官方资料和官方前端为依据：

- [NovelAI Image Generation OpenAPI](https://image.novelai.net/docs/doc.json)：图像生成、流式生成、标签建议、返回格式、请求参数和错误结构。
- [NovelAI Account 文档](https://docs.novelai.net/en/text/usersettings/account/)：Persistent API Token 的生成、复制和旧 Token 失效行为。
- [NovelAI Image Models 文档](https://docs.novelai.net/en/image/models/)：V4.5、V5 模型能力与提示词限制。
- [NovelAI Image Generation 文档](https://docs.novelai.net/en/image/)：网页版图像生成设置和用户流程。
- [NovelAI Subscription 文档](https://docs.novelai.net/en/subscription/)：订阅等级、ImageAnlas 与免费生成条件。首版暂不实现费用计算。
- [NovelAI Terms of Service](https://novelai.net/terms)：自动化和服务负载边界。
- NovelAI 当前官方网页发布的前端脚本：用于核对公开 OpenAPI 尚未完整描述的流式调用形态。前端构建文件会随发布改变，因此不能作为 PocketNAI 的永久协议常量。

最终开发前仍需要使用用户自己的合法账户做一次本地脱敏协议验证；该验证不得把 Token、完整 Prompt 或生成图片写入诊断日志。
