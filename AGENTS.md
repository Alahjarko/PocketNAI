# PocketNAI 开发约定

面向在本仓库工作的编码助手。产品范围见 [docs/PocketNAI-规划书草案.md](docs/PocketNAI-规划书草案.md)，技术取舍见 [docs/PocketNAI-技术决策记录.md](docs/PocketNAI-技术决策记录.md)。

## 每次改完代码后的固定动作

**不要停在"编译通过"或"问用户要不要装"。每次完成一项改动，按顺序做完全部四步：**

1. `./gradlew :app:testDebugUnitTest` —— 单元测试必须全绿；
2. `./gradlew :app:assembleDebug` —— 产出 APK；
3. **直接安装到模拟器**（用户明确要求，无需再问）：
   ```bash
   adb -s 127.0.0.1:5559 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   多开实例时 `emulator-5558` 也一并安装；
4. 需要看界面时用 `adb shell screencap` 截图确认，而不是只靠读代码推断。

用户的本机环境（勿假设为默认值）：

- Android SDK：`C:\Users\13911\AppData\Local\Android\Sdk`
- Gradle 发行版：`.tooling/gradle-8.11.1/bin/gradle`（本机没装全局 gradle，`gradlew` 也可用）
- 模拟器：MuMu，`adb connect 127.0.0.1:5559`（另有 `emulator-5558`）
- Git Bash 下调用 adb 必须 `export MSYS_NO_PATHCONV=1`，否则 `/sdcard/...` 会被改写成 Windows 路径

## 硬性约束（改动时不得违反）

### 生成成本（优先级最高，先于其它所有便利性考虑）

用户提供了一个**实验账号**：在同时满足下列四个条件时，V4.5 Curated 的生图是**免费**的。

| 项 | 必须是 |
|---|---|
| 模型 | `nai-diffusion-4-5-curated` |
| Resolution 档位 | `Normal`（横 1216×832 / 竖 832×1216 / 方 1024×1024） |
| `steps` | 23 |
| Prompt Guidance（请求里的 `scale`） | 7.0 |

**任何一项偏离都会产生真实 Anlas 消耗。** 因此：

- 用这个账号做验证时，必须逐项确认这四个值；不得顺手改参数，不得批量或循环跑图；
- **费用未知或确定要花钱的调用一律不得自动发起**，只能由用户在界面上手动触发：
  其它三个模型、`Large` 档位、Img2Img / Vibe Transfer / Precise Reference、
  `/ai/encode-vibe`、`/ai/upscale`、`/ai/augment-image`；
  （`/ai/generate-image-stream` 已于 2026-09-16 在 V4.5 Curated + 免费组合下实测不扣费，
  见技术决策记录 §24，不再属于此类）
  2026-09-18 新加的两项，价格已在当晚核对清楚（技术决策记录 §30）：
  **高清放大**按源图面积收 **1-4 Anlas**（Opus 的免费单张不覆盖它，确认框显示确切数字）；
  **多角色 Characters** 在免费组合下**不收费**（官方计价里没有 `char_captions` 项）。
  但"发起"这件事没变：超分要花钱、生成一律要用户点，两者都**只能由用户手动触发**；
- 单元测试与任何自动化流程一律使用 MockWebServer 或假实现，**永不触网**；
- 免费的那一次生成只用于验证"链路是否通"，不用于探索参数效果。

⚠️ **2026-09-14 补充："免费"是订阅权益，先确认订阅状态再依赖它。**
当天读 `GET /user/subscription` 得到的原始读数是
`tier 0 / active false / accountType 0 (RETAIL) / expiresAt 0` —— 按官方判据这**不是**订阅账号，
生成会正常扣费。上面那张"免费组合"表是账号所有者在订阅有效期内给出的经验值，
**不是账号的永久属性**。动手前先在设置页（或余额弹层）看订阅等级那一行：
它显示"本机指定"或"有订阅权益"时才谈得上免费。
如果读数与用户描述不一致，**不要自己判断谁对**，按上面"费用未知一律不自动发起"处理。

### 安全

- **Token 绝不进入日志、数据库、图片元数据、剪贴板或崩溃信息。** 日志只允许记录请求方法、路径、状态码与耗时。
- 不引入 OkHttp 的 `HttpLoggingInterceptor`（防止有人顺手开到 BODY 级别）。网络日志走 `RedactingHttpLogger`。
- Prompt 在日志里只记录长度与哈希指纹。
- `persistent-api-token.txt`、`local.properties` 已被 `.gitignore` 排除，不要把它们加进版本库。
- Token 所在首选项文件固定叫 `pocketnai_secure`，必须与 `res/xml/backup_rules.xml`、`data_extraction_rules.xml` 的排除项保持一致。

### 数据库

- 当前 schema 版本 **7**。新增表（如 `prompt_favorites`、`reference_images`、`favorite_images`）用独立 `CREATE TABLE`，
  不要触碰既有表。v3 → v4 新增了 `reference_images` 表与 `generations.mode` 列（可空，见下）；
  v4 → v5 给 `generations` 加 `outputWidth/outputHeight`；v5 → v6 新增 `favorite_images` 表；
  v6 → v7 新增 `anlas_transactions` 表（Anlas 消耗流水，2026-09-18）。
- **`generations` 表绝不能重建（DROP / RENAME）。** `generated_images` 以 `ON DELETE CASCADE` 引用它，重建会连带删除用户的图片记录。
- 加列用 `ALTER TABLE ... ADD COLUMN`；删列需要 SQLite 3.35+，`minSdk 26` 不满足，所以宁可保留遗留列。
- Room 的表校验要求实体列与真实表列**完全一致**，多一列少一列都会失败。因此遗留列必须留在实体里。
- 升级必须写显式 `Migration`，不使用破坏性迁移。schema 导出到 `app/schemas`。
- 新增的字段如果是给既有表加列，实体侧声明成可空可以避开 Room 的默认值比对陷阱
  （`generations.mode` 就是这么加的：迁移里只 `ADD COLUMN` 不带默认值，再用一次
  `UPDATE` 回填 `TXT2IMG`）。
- **SQLite 的外键约束默认是关的**，应用里靠 Room 生成的实现执行 `PRAGMA foreign_keys = ON`
  才生效。因此仪器化迁移测试里要验证 `ON DELETE CASCADE`，必须自己在裸库上再执行一次
  `PRAGMA foreign_keys = ON`，否则测到的是"开关没开"而不是"级联没生效"（会误报失败）。

### 参考图的请求形态

- 图生图：`action` 换成 `img2img`，`parameters.image` + `parameters.strength`。
- Precise Reference：`action` 保持 `generate`，发 `director_reference_*` **五个数组**。
  数组按下标一一对应，**任何一项缺失都不能跳过**（必须补默认值），
  否则"这个角色的强度"会落到"那个角色"上，而服务端不报错。
- 黑边补齐在**导入时**做（`ImageTransform.DirectorCanvas`），缩略图因此就是提交图；
  提交时再套一次同样的 Letterbox 是恒等变换，不要"聪明地"跳过。
- Vibe Transfer：`action` 也保持 `generate`，发 `reference_*_multiple` **三个数组**，
  内容是 `encode-vibe` 的产物 base64（实测服务端收编码产物，不收原图）。
  编码缓存在 `files/vibes/<hash>.vibe`，键含模型 + 图片 sha256 + Information Extracted。
- **Vibe 与 Precise Reference 不能同时发**：服务端会拒绝
  （`cannot mix reference and director_reference at the same time`）。界面必须互斥。
  Vibe 与图生图可以同时发。

### 局部重绘（Inpaint）

**一句话：功能已完成并真机验证通过（2026-09-14 晚），四个模型全部开放。**
请求形态已与官方网页前端对齐且被服务端接受，别再重新设计。

**请求形态（已实测验证，技术决策记录第十七、十八节）**

- `action` 用 `infill`；**`model` 必须换成专用的 `-inpainting` 模型 ID**
  （`ImageModel.inpaintingApiModelId`，例如 `nai-diffusion-4-5-curated` →
  `nai-diffusion-4-5-curated-inpainting`）。用常规模型 ID 发 infill 必然 400
  （`doesn't support action infill`）—— 历史上的拒绝全是这个原因。
  映射含一条反直觉项：V5 Curated 回落到 `nai-diffusion-4-5-curated-inpainting`
  （`5-curated-inpainting` 服务端不存在，suggest-tags 实测 400）。
- 底图放 `parameters.image`、蒙版放 `parameters.mask`；重绘路径还按官方恒发
  `add_original_image=false`、`sm=false`、`sm_dyn=false`、`extra_noise_seed=seed-1`。
- 重绘强度默认 **1.0**（官方滑块初值，计价乘数也读它）：等于 1 时**不发**嵌套
  `img2img` 对象；不等于 1 时发 `img2img={strength, color_correct: true}`。
- 蒙版与底图**同尺寸**、**硬边（关抗锯齿）**、二值语义，约定 `PAINTED_IS_WHITE`
  （白=重画、黑=保留、不透明 PNG）—— **已实测确认**：蒙版涂 3.91%，出图蒙版内
  98.9% 重画、蒙版外仅 0.74% 变动。翻转点在 `MaskConvention`。
- **落盘前必须做 8×8 隐空间网格对齐**（`MaskImageProcessor.snapToLatentGrid`，
  最近邻缩 1/8 再放回，与官方前端管线等价）：任意精度的边界经服务端下采样会产生
  "半涂半不涂"的边缘格，模型会在那里发明过渡材质（用户实测的"白色不明材质边界"）。
  编辑器实时预览保持平滑（差距最多半格），网格对齐只作用于落盘的提交图。
- 扩张用"笔刷半径加 N"实现（Minkowski 和），**不要**改成对位图做形态学卷积 ——
  那样会毁掉实时预览。橡皮不参与扩张。
- 重绘与参考条件（Precise Reference / Vibe）互斥；换底图必须作废旧蒙版，
  **移除底图同理**（`onRemoveReference` 连坐清蒙版）。蒙版与底图必须同时在场：
  缺底图的残留蒙版曾经漏过校验，发出"常规模型 ID + infill"的自相矛盾请求被
  服务端 400（技术决策记录 §18.5）。校验侧有 `MissingInpaintBase` 兜底，
  构造侧 action 与 model 共用同一个"载荷是否齐备"的判定，不许再脱钩。
- 计费：Opus 免费单张对重绘同样生效（已实测：余额 400 → 400），
  计算器按官方规则（强度乘数 = 重绘强度）即可，无附加费。

**历史教训：三条死路（别再试）**

| 试过的路径 | 结果 |
|---|---|
| `infill` + 常规模型 ID（Curated 与 Full 都试过） | `HTTP 400 Model ... doesn't support action infill` —— 模型 ID 错了，不是功能没了 |
| `img2img` + 蒙版 | **请求成功、出图，但蒙版被完全忽略**（定量证据：蒙版覆盖 3.55%，输出却变了 52.6%、96.7% 的变化在蒙版外） |
| 无鉴权探测 action 是否开放 | 401 先于模型校验，学不到东西 |

第三条尤其危险：`img2img` 会把蒙版字段当空气，用户会得到"整张图被重画"的结果 ——
比没有按钮更糟，因为它看起来是成功的。

**不要做的事**

- 不要绕过 `ImageModel.inpaintingApiModelId` 给 `infill` 发常规模型 ID；
- 不要把 `img2img` + 蒙版当作重绘的"降级路径"（蒙版会被静默忽略）；
- 不要删除编辑器与蒙版渲染的代码。

### 自定义分辨率

- **一个尺寸不能承担三种含义**：`params.size` 是**提交给 NovelAI 的画布**（64 对齐，
  请求、计费、底图预处理都用它），`params.outputSize` 是**用户要的最终尺寸**
  （可空；非空才裁切）。`CustomResolution`（编辑器状态）只决定输入框显示什么。
  写入 `params` 的唯一入口是 `GenerateViewModel.applyCustomResolution`，不要在别处改尺寸。
- **对齐方向**：精确模式用**向上**取整（`ceilToStep`），不是官方的"取最近" ——
  用户要 `1050×1050` 时最近值 `1024` 比目标还小，根本没法裁。官方的"取最近"只在
  仿官方模式（不裁切）里用。
- **裁切规则只定义一处**：`ResolutionPlanner.centeredCrop`。居中，奇数差值多给右下 1 px。
  规划器与落盘后处理都调它 —— 两处各写一遍迟早会不一致。
- **裁切必须在落盘之前**（`OutputImageProcessor`，在 `commitImages` 之前）：
  历史文件、数据库宽高、缩略图、相册导出都以落盘那一刻为准。
- **不裁切时一个字节都不动**：预设尺寸的历史不受影响，SHA-256 与体积保持原样。
- **裁切后必须写回 NovelAI 元数据**：`Bitmap.compress(PNG)` 会丢掉 `tEXt`，不写回去
  会让元数据导入在"裁过一次"之后静默失效。写回走白名单（见 `PocketNaiOutputMetadata`），
  另加一条 `pocketnai_output=output=..;generation=..;crop=x,y`；
  **绝不篡改 NovelAI 的 `Comment` 里的尺寸**（那是生成画布，改掉同 Seed 就复现不出来）。
- **尺寸约束分两套**：官方（步长 64、面积 3,145,728）与本地护栏（边长 256–2048）。
  注释里必须写清哪条是谁的，不要把本地限制说成 NovelAI 的限制。
- 元数据导入遇到"不在预设但合法"的尺寸时**按自定义尺寸接住**，不要悄悄取整。
- **Custom 是分辨率下拉里的一项**（`Normal` / `Large` / `Custom`），不是旁边的独立按钮 ——
  官方也是这么放的。独立 chip 试过：它在一行里会被挤成没有文字的空药丸。
  选中 Custom 时横竖方按钮要整体禁用（方向由宽高决定）。
  用文件内的 `ResolutionSizeOption` 表达"档位 / 自定义"，**不要给 `ResolutionTier` 加 `CUSTOM`**。

### 图片元数据导入（选图时读参数）

- **必须在图片归一化之前读原始文件**：`ReferenceImageProcessor` 会解码再重新编码 PNG，
  `tEXt` 文本块在那一步全丢。正确顺序是 `onReferencePicked` 里先
  `metadataInspector.inspect(source)` 再 `referenceImporter.import(source)`；
  反过来功能会**静默失效**（不报错，永远没有元数据）。
- 只有 `Software` 含 `NovelAI` 才算元数据（与官方一致）。`Comment` 解析失败不算致命：
  仍给出"这是 NovelAI 图"，只是没有参数。
- **字段白名单**，只取我们认识的：`prompt/uc/width/height/steps/scale/cfg_rescale/
  sampler/noise_schedule/seed`。不认识的值留空 + 逐项说明，**绝不反射进请求**。
- 模型靠 `Source` 的哈希查表（`NovelAiModelHashes`，已用本机数据验证）——
  `Source` 的可读名字分不出 Curated/Full。认不出的 `Source` 保持当前模型不变。
- **尺寸不是内置预设时不改成"最接近的"**：尺寸一变，同 Seed 也复现不出原图。
- **质量标签去重**：按 `|` 分块，每块都以候选后缀结尾才剥离并沿用对应预设；
  都不匹配就保留原文并把质量标签设为 `None`（否则会重复追加一次）。
  用我们自己的后缀表（`QualityTagsOption.appendedText`），不要用官方那张表 ——
  V4.5 Curated 的官方后缀与我们不同。
- **导入后模板与提交值必须一致**：编辑器绑定的是 `promptTemplate`，
  而质量标签是在提交时追加到 `params.prompt` 的。只改一个会让标签翻倍。
- **Characters 只报告数量，不导入**，界面也不显示官方的 `Characters` / `Append`：
  多角色提示词没实现，硬拼会丢角色的独立反向词、位置与顺序。
- 导入**不自动生成**（同 Seed 同参数很容易再产出一张几乎一样的图，而那要花 Anlas）。
- 元数据是外部输入：读取有限额（单块 1 MiB、解压 1 MiB、总量 2 MiB），
  且**不把 `Comment` 原文写进日志**（AGENTS.md 的安全约束）。

### 参考图的文件存储

- 参考图与 encode-vibe 产物是**内容寻址**的：`files/references/<sha256>.png`、
  `files/vibes/<缓存键>.vibe`。同一张图被多次使用只占一份磁盘。
- 因此它们**不能随生成记录一起删**（可能被别的历史引用），只能由
  `GenerationFileStore.cleanupOrphans` 按"仍被引用的路径集合"回收；
  那个集合必须来自 `GenerationDao.allReferencePaths()` 的完整查询，漏一条就会误删。
- 不要给参考图做"按生成记录分目录"的改动：那会让重复使用同一张图时磁盘翻倍。
- 启动清理的存活集合 = 数据库引用 ∪ **草稿里挂着的参考图**（`LiveReferencePathsProvider`）。
  少了后者，用户"选好图 → 重启应用 → 生成"必然失败并报"参考图已不在本机"。
  它是 `GenerationRepository` 的必填参数，不要图省事给它默认空集合。
- **`reference_images` 行的主键是 `"<generationId>:<role>:<ordinal>"`，不是素材的 id。**
  素材（`ReferenceImage.id`）会被草稿与"复用参数"跨生成复用，而插入用的是
  `OnConflictStrategy.IGNORE` —— 拿素材 id 当主键会让第二次生成静默丢掉参考图行。
  跨生成判断"是不是同一张图"要用 `sha256`，不要用 id。

### 流式中间预览（技术决策记录 §24）

- **默认关闭**（2026-09-16 用户真机实测后决定）：功能已验证可用，但拿到的是服务端
  **原始采样帧**，观感与官方网页版（用 MessagePack 流 + 对中间图做过平滑）差距明显，
  且生成只要几秒时画面一闪而过。`SettingsStore` 的默认值因此是 `false`
  （设置页标注"实验性"）——**没有明确理由不要改回 `true`**；
- **协议已实测**（2026-09-16，免费组合）：`POST /ai/generate-image-stream`，
  请求体与普通生成相同并显式声明 `parameters.stream = "sse"`；SSE 的 `event:` 字段
  区分 `intermediate` / `final`，负载 JSON 键为 `event_type, gen_id, image, samp_ix, sigma, step_ix`
  （final 帧没有 sigma / step_ix）；
- **中间图是 JPEG、final 才是 PNG**：预览解码必须宽容（PNG / JPEG / WebP / GIF 魔数 +
  剥掉 `data:image/...;base64,` 前缀）。**不要改回只认 PNG** —— 中间图会被逐帧丢弃，
  而 final 正常、生成照样成功，看起来像"功能没坏"，只有真机能发现；
- 进度：服务端只给 `step_ix`（**不给总步数**），百分比用本次请求的 steps 自己算；
- 预览写 `cache/previews/<generationId>/0001.png`（同序号覆盖），生成收尾与启动清理时删除；
  **预览不进入 files 目录、不参与历史清理的存活集合**；
- 流式失败**不自动降级**到普通 ZIP（规划书 §6.3："不自动发起第二次生成"），只失败并计数；
  连续 3 次自动关闭流式预览（`SettingsStore.recordStreamingFailure`），用户可在设置页重开；
- DEBUG 诊断日志（tag `PocketNaiStream`）**只允许记结构**：事件名、data 长度、
  JSON 顶层键名、图片 magic —— 不得添加任何字段值。

### 网络

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

### 网络代理（公益节点 / 自定义，2026-09-18）

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
- 排障工具：`SocksProxyProbeTest`（androidTest，**唯一允许联网的仪器化测试** ——
  只发 GET 到 NovelAI 首页测 SOCKS 建连，不涉及生成）；运行时诊断看 logcat 的
  `PocketNai/Proxy` tag（只记结构：模式/类型/端口匹配，**不记端点与凭据**）。
- 流量：`ProxyTrafficListener`（EventListener body 计数）只挂在代理 client 上；
  公益模式每日 750 MB 上限（本机统计、跨天重置），超限后 `ProxyQuotaInterceptor`
  拒绝新请求并映射成 `PROXY_QUOTA_EXCEEDED` —— 文案要指回"今日额度"，
  绝不能静默当网络错误处理。

### 检查更新与发布（GitHub Release）

- 发布渠道有两条，**构建号同源**（都取"已有 Release 里最大的 `build-N` 加 1"），因此不会撞号：
  1. **日常用本地发布**：`scripts/publish-release.ps1`（或双击根目录的"发布新版本.bat"）——
     跑单测 → 构建 APK（带下一个构建号）→ 建 Release 并上传，一条龙，不用等云端排队；
  2. 云端 `.github/workflows/build-release.yml` 保留为备份，在 Actions 页面**手动触发**
     （workflow_dispatch）。之前的"push 即构建"已取消：与本地发布并行会撞号，且云端排队慢。
  两条路径发布后都只保留最近 3 个 Release。固定分享链接（永远指向最新构建，适合直接发给用户）：
  `https://github.com/Alahjarko/PocketNAI/releases/latest/download/PocketNAI.apk`。
- **签名密钥绝不能换**：两条路径都必须用本机那把 debug keystore，与所有既有安装签名一致 ——
  换了密钥，新包在用户手机上**无法覆盖安装**，只能卸载重装（丢历史与凭据）。
  本地发布用 AGP 默认路径即可；云端通过 `PNAI_KEYSTORE_PATH` 等环境变量**显式指定**
  keystore 文件（2026-09-18 实测：只把文件放 `~/.android/` 不行，构建用了自动生成的新密钥，
  被应用内的签名校验拦下）。`DEBUG_KEYSTORE_BASE64` 存仓库 Secret，CI 日志会打印其 sha256
  供与本机 `sha256sum ~/.android/debug.keystore` 对照。
- **不能无条件给 `debug.signingConfig` 赋值**（包括赋 `null`）：那会覆盖 AGP 预置的默认
  debug 签名，本地构建产出 `app-debug-unsigned.apk`（构建成功但没签名，2026-09-18 踩中过）。
  正确写法是 `signingConfigs.findByName("pinned")?.let { signingConfig = it }`。
- 版本号：发布时注入 `PNAI_VERSION_CODE` / `PNAI_VERSION_NAME` 环境变量
  （`app/build.gradle.kts` 读取；本地脚本与云端 workflow 都用"已有 Release 里最大编号 + 1"），
  不带变量构建时保持 `1` / `"0.1.0"`。
  **Release tag 里的数字与 APK 的 versionCode 必须同源** —— 应用内更新检测就靠这个比较。
- 应用内检查更新（`domain/update` + `data/update` + `ui/update`）：
  - 判据是**构建号比较**（tag `build-42` → 42 与 `BuildConfig.VERSION_CODE` 比大小），
    不比较版本名字符串；解析与判定都是纯函数（`UpdateEvaluator`），有单测钉住；
  - 仓库地址在 `BuildConfig.UPDATE_REPO`（`app/build.gradle.kts`），换仓库只改这一处；
  - **只发匿名 GET**（`releases/latest`），不带凭据、不带任何用户数据；这是应用里
    唯一会自动发起的网络请求（启动后延迟数秒静默检查，失败不打扰，设置页可手动检查）；
  - "稍后"记进 `SettingsStore.updateDismissedVersionCode` —— 同一个构建不重复弹，
    出现更新的构建才再提示；
  - 下载后**必须做签名校验**（与当前安装比对，不一致就丢弃）：这是"这个包不该装"
    而不是网络问题，文案要分开；下载与校验失败都不自动重试；
  - 更新包放 `cache/updates/`（不是用户数据，不进 files/、不参与历史清理），
    经 FileProvider（只暴露这一条路径）交给系统安装器；
  - 安装前检查 `canRequestPackageInstalls()`：没有"安装未知应用"授权时先跳系统设置页，
    用户授权回来后再点一次即可（文件已缓存，不会重新下载）。
- 更新检查**不受**"费用未知不得自动发起"约束（它不碰 NovelAI、不花 Anlas），
  但**生成相关的任何自动化仍然一律禁止**。

### 余额与费用

- 余额是服务端事实，费用是本地推算，**两者严格分开**：余额只在内存缓存（5 分钟），
  不写 Room、不写 SharedPreferences —— 否则会把上一个账号的余额显示给下一个账号。
- 余额读取失败**不得阻止生成**，也不得把界面上的余额清零；能不能生成始终由服务端 402 决定。
- 费用计算器只能返回四态之一（免费 / V5 额度 / 预计 Anlas / 待确认）。
  定价公式的入口是 `PaidAnlasFormula`，**当前实现是 `NovelAiPaidAnlasFormula`**：
  式子与免费规则是 2026-09-14 从官方网页前端 bundle 反解出来的（见技术决策记录第十六节），
  不要再按"社区库公式"或"人工读网页费用标签"的路子重做一遍。
- **订阅状态**：官方判据是 `accountType ∈ {B2B,SERVICE,SUPPORT,ADMIN} || (expiresAt > now && tier > 0)`，
  **`active` 字段完全不参与判断**；`tier` 的整数含义是 `0/1/2/3 → 无/Tablet/Scroll/Opus`。
  服务端读数可能和用户实际买到的权益不一致，因此设置页有**手动覆盖**
  （`SettingsStore.subscriptionOverride`），手动值直接取代读数。
  要改订阅判断只改 `SubscriptionStatusResolver` 一处，不要在计算器里再塞第二套。
- **附加费按功能而不是按图数计**，规则以官方前端为准：Image2Img 不额外收费（已用余额观测印证）；
  Precise Reference **每张参考图 × 每张输出图 5 Anlas**；Vibe 超出 4 张的部分每张 +2，
  另加"未编码的 vibe"每张 2 Anlas 的编码费（已编码的不再收，判定入口是
  `GenerationRepository.isVibeEncoded`，与真正编码用的是同一个缓存键）。
- **免费单张规则（以官方前端为准）**：`面积 ≤ 1024×1024 && steps ≤ 28 && tier 是 Opus && 有订阅权益`
  （V5 还要 `usage.isNegative == false`）。**没有"无底图"这一条**（图生图同样免费，已实测印证），
  **也不是整单免费**（批量只免 1 张）。
- **"订阅打 20% 折扣"指的是买 Anlas 的美元价，不是生成扣费**：官方定价页那一行叫
  Anlas Purchase Discount，前端里订阅价是原价的 ~79%（$4.79 → $3.79 等），
  而计价函数里**没有任何 0.8 系数**。给它加折扣会把实际扣费报少 20%。
  用户再提这件事时直接引技术决策记录 §16.6。
- **SMEA 附加费不存在（2026-09-18 核对，原"唯一未实测项"已关闭）**：公式里确有
  `sm ×1.2 / sm+sm_dyn ×1.4`，但 V4/V4.5/V5 的能力表里**没有 smea 能力位**，
  官方前端组装请求时会 `delete sm/sm_dyn` —— 这四个模型的倍率恒为 1。
  `AnlasPricingContext.smeaMultiplier` 保持 1.0 就是官方行为，不需要再做费用核对（§30.3）。
- **高清放大按源图面积收 1-4 Anlas**（≤1024²→1、≤1747627→2、≤2446678→3、≤3145728→4；
  超过 1536×2048 官方直接禁用），**Opus 的免费单张不覆盖它**。
  价格表在 `domain/billing/UpscaleCost`，确认框显示确切数字（§30.1）。
- **免费判定里的 `characterRef` 是死字段**：官方前端只读不写它，因此
  Precise Reference **不**取消免费单张（与 407→402 的实测一致），多角色也不取消。
  附加费只有三项：Precise Reference（5/张/输出）、Vibe 超 4 张（2/张）、未编码 Vibe（2/张）。
  别照字面把 `!characterRef` 读成"带参考图就不免费"（§30.2）。
- **`/ai/augment-image` 是导演工具**（bg-removal / declutter / lineart / sketch / colorize /
  emotion / pixel-snap），**前端不计价也不显示价格**，纯服务端定价；官方文档同样不给数字。
  要接入就必须"价格未知 + 用户手动触发 + 事后用余额差反推"（§30.5）。
  另注：官方的 **Enhance 不是这个端点**，它是普通 img2img 生成 + 放大分辨率，按普通公式计价（§30.4）。
- 模型能力位：图生图四个模型都支持；**Vibe Transfer 与 Precise Reference 目前只有 V4.5**。
  界面要隐藏 V5 上的入口，`GenerationRequest.validate` 也要在本地拦一次。
- **Anlas 消耗流水**（`anlas_transactions`，v7 新增）：记录的是"本机观察到的"消耗与余额快照 ——
  **扣费事实仍以服务端为准**，这里只是给用户一个账目参考。写入发生在生成/超分成功之后，
  写流水失败不得影响生成结果；金额用的是本地计价器的预估（与生成前报价同一套公式），
  余额是当时内存里的服务端读数。

### 质量标签

- 质量标签是**追加到提示词末尾的文本**（`very aesthetic, masterpiece, no text` 等），由客户端拼接，不是 API 开关。
- 因此 `NovelAiRequestBuilder` 把 `qualityToggle` 固定发 `false`，避免服务端重复追加。

### 提示词拼接

- 任何"把一段文本接进提示词"的需求都必须调用 `PromptComposition.append`，
  **不要另写一份拼接逻辑**。曾经质量标签与收藏片段各有一份实现，
  差异最终会变成"有时开头多一个逗号"这类难查的小毛病。
- 收藏提示词只有一个入口（提示词框右上角的书签）：有选中存标签，没选中存整条。
  因此两个输入框都必须用 `TextFieldValue` 跟踪，不能退回普通字符串——
  选区信息是这条判断的唯一依据。

### 提示词权重高亮

- 编辑里的绿/红圆角底纹由 `PromptWeightScanner` 算区间、`WeightHighlightedTextField` 画。
  权重计算一律走 `EmphasisSyntax.strengthOf`，**不要在扫描器里再写一套倍率**。
- **不要改回 `OutlinedTextField` + `OutlinedTextFieldDefaults.DecorationBox`。**
  那个 `container` 参数是**边框图层**而不是文字内容，`DecorationBox` 自己会放置输入框；
  按"内容是 container"的写法会得到**文字画两遍、边框消失**的界面（已实测）。
  现在的做法是自建边框 + 把 Canvas 与输入框放进同一个 `Box`，用 `matchParentSize` 保证
  两者同尺寸同原点，**因此不依赖任何 Material 内边距常量**。
- 输入框必须 `fillMaxWidth()`：底纹的换行位置来自"排版宽度 == Box 宽度"这个等式。
- **文字颜色必须显式设为主题色**（`textStyle = LocalTextStyle.current.copy(color = onSurface)`）：
  裸 `BasicTextField` 不像 Material 组件那样解析未指定的颜色，`LocalTextStyle` 的默认颜色是
  `Unspecified`，底层按黑色渲染 —— 浅色主题下恰好正确，深色主题就是"黑底黑字"
  （2026-09-16 实测并修复，技术决策记录 §22.6）。改这个输入框时别把这行删了。
- 数字权重 `0.9::tag ::` 是**用户要求的语法，未经核对服务端是否认**（技术决策记录 §22.4）。
  它只用于高亮：**不替用户改写提示词，也不要加"插入数字权重"的按钮**。
  核对它需要在官方网页版手动验证，属于用户手动发起的项。
- 目前提示词框"随内容长高、由外层滚动"，底纹才能对齐；给它们加固定高度 + `maxLines`
  会让底纹在内部滚动时错位，那时必须一并处理滚动偏移。

### 提示词输入框的状态（不要镜像文本）

- **输入框的文本与光标归输入框自己所有。** 绝不要写"文本一变就把 ViewModel 的文本
  同步回输入框"的 effect：每次按键都会把文本推给 ViewModel，那种写法等于把刚写出去的东西
  再收回来，而 effect 的执行晚一拍、收到的是**上一拍的值**，于是输入框被整体重置、
  光标跳到别处。2026-09-16 实测：连续退格时"跳行"，根因与取证见技术决策记录 §25。
- 外部**整体替换**文本只有三条路：复用历史参数、导入元数据、收藏夹填充。它们统一靠
  `UiState.textRevision`（代数）+1 表达，输入框用 `remember(textRevision)` 重建。
  写新的"整体替换"入口时**必须 +1**（在 `GenerateViewModel` 里写，别在界面拼）；
  打字路径 `onPromptChange` / `onNegativePromptChange` **绝不能**动它 ——
  在那里 +1 会让输入框每次按键都重建、光标永远停在文末。
- 界面上"由外部塞进输入框"的动作（收藏夹填充）走 `GenerateViewModel.replacePromptText`，
  不要用 `onPromptChange`：后者不会让输入框知道文本换了，界面会停在旧内容上。
- 导入元数据时**模板与 `params` 必须同时写**（正向与负向都是）。只写 `params` 的话，
  提交时 `startGeneration` 会用模板重新解析一遍并覆盖它 —— 导入的值既不显示也不生效
  （负面提示词曾经如此静默失效，技术决策记录 §25.5）。

### 画廊检索与收藏

- 筛选规则是纯函数 `GallerySearch.matches`（`domain/model/GalleryFilter.kt`），在
  `GalleryViewModel` 里用 `combine` 套一层；**不要改写成带可选参数的大 SQL** ——
  纯函数能在 JVM 单测里断言，而 SQL 只能靠跑起来才知道对不对。
- 关键词规则：大小写不敏感、`_` 与空格互相等同、多个词是**全部命中**。
  改这三条中的任何一条都要同步改 `GallerySearchTest`。
- 收藏单独一张 `favorite_images` 表（主键是**图片 id**），靠外键 `ON DELETE CASCADE`
  跟着图片消失；**不要**给 `generated_images` 加收藏列。
- **"从历史选图"对话框必须用自己的 ViewModel 实例**（`viewModel(key = "history-image-picker")`）：
  它与画廊共用默认的 ViewModelStoreOwner，共用实例会让画廊的筛选条件
  悄悄作用到那个**没有任何筛选控件**的对话框上。

### 多角色提示词（Characters，2026-09-18）

- 请求形态：`v4_prompt.caption.char_captions[]`，每项 `{char_caption, centers:[{x,y}]}`；
  **有角色时 `use_coords` 为 true**；负向走 `v4_negative_prompt` 的同构数组，
  每角色的负向词取 `character.negativePrompt`（空则发空串）。
- 位置只有**五档**（左/偏左/居中/偏右/右）映射到 x，没有自由拖拽、没有 y 轴
  （官方网页是 5×5 网格；我们保持简单）。改坐标换算要同步 `CharacterPrompt` 与请求构造两处。
- 角色数量上限 **5**。
- **计费已核对（2026-09-18 晚，技术决策记录 §30.2）：免费组合下多角色不收费。**
  官方免费判定读的 `characterRef` 是个只读不写的死字段，计价组装里也没有 `char_captions`
  的附加费项 —— 多角色与普通生成同价。有单测钉住（`AnlasCostCalculatorTest`）。
  真实调用仍然只能由用户手动点；第一次跑时顺手看一眼余额，确认订阅权益还在。

### 高清放大（Upscale，2026-09-18）

- 入口在详情页（与"局部重绘"并排）。**必须先弹确认框**，确认框显示**确切预计 Anlas**
  （按源图面积 1-4，见 `domain/billing/UpscaleCost`），用户点"确认放大"才发请求。
- **请求体只有三个字段**：`image` / `model`（恒为 `nai-diffusion-5-curated`，是超分模型，
  与被放大图的生成模型无关）/ `declared_blur_sigma`（恒为 0）。
  **没有倍数与目标尺寸** —— 官方固定放大 4 倍，别再加回 `width/height/scale`
  （首版就是发了这三个臆造字段，功能等于没通；技术决策记录 §30.1）。
- 源图面积超过 3145728（1536×2048）时**禁用确认**并说明原因，不要发注定失败的请求。
- 结果作为**一张新图片**入库（新 generation 记录，模式标签"高清放大"），原图不动。
- 请求在 `OkHttpNovelAiApi.upscaleImage`，响应的 ZIP 与生成链路同构。
- **超分不吃 Opus 的免费单张**：1-4 Anlas 照收，所以它永远属于"要花钱的调用"。

### 分享、批量操作与图片清理（2026-09-18）

- 分享走系统 `ACTION_SEND`（多张时 `ACTION_SEND_MULTIPLE`），文件经 FileProvider 的
  `files/generations/` 路径授权。`res/xml/file_paths.xml` **只允许两条路径**：
  `cache/updates/`（给安装器）与 `files/generations/`（给分享）——
  加任何新路径前先确认它不会暴露 Token（`pocketnai_secure`）、数据库或参考图缓存。
- 画廊"多选"模式的批量删除只删**本地副本与记录**（已存系统相册的副本不动，
  确认框里已写明）；不要给它加"同时删相册"的行为。
- **清理生成图片**是破坏性操作：默认勾选"保留已收藏的图片"，只删未收藏的普通生成图。
  它与启动时的"清理孤儿文件"是两回事（后者只删没有任何引用的目录），不要混在一起。

### 凭据与认证（双认证模式）

- **绝不允许**把密码、Access Key、PST 或 Access Token 写进日志、数据库、SharedPreferences
  （`pocketnai_secure` 里的 Keystore 密文除外）、图片元数据、剪贴板、异常 detail 或测试代码。
  也不要读 `persistent-api-token.txt`，不要代发真实登录或真实生成请求。
- **不得改动的兼容性常量**：首选项文件名 `pocketnai_secure`、Keystore alias
  `pocketnai_token_key`、密文键 `token_iv` / `token_ciphertext`。改任何一个都会让旧安装
  已保存的 PST 无法读取，必须同步 `res/xml` 的备份排除规则。
- 登录错误与生成错误是**两套映射器**，不要合并：生成接口的 403 是"凭据失效"，
  登录接口的 403 是"风控/需要额外验证"。超时同理（`REQUEST_TIMEOUT` vs `TIMEOUT_UNCERTAIN`）。
  两边都有用例钉住这个差异。
- Access Key 派生的前 6 个字符必须按 **Unicode 码位**切，不能用 `take(6)`（按 UTF-16 单元）。
  `emoji-leading` / `emoji-inside` 两个向量专门守这条。
- 改派生相关代码前先看 `DerivationDiagnosticTest`：它把 preSalt / BLAKE2b salt / Argon2 输出
  分三步与参考实现对账，能立刻定位是哪一步跑偏。
- 生成链路只认 `credentialStore.load()?.token`，不应该知道邮箱、密码或派生算法的存在。
- **多账号**（2026-09-18）：每个账号的密文存在 `account_<id>_iv` / `account_<id>_ciphertext` 等键里，
  但**顶层的 `token_iv` / `token_ciphertext` / `token_type` / `token_hint_*` 必须继续同步维护**
  （激活账号的镜像）—— 那是兼容层：旧安装升级时靠 `ensureLegacyMigrated` 建索引，
  新代码读取也不该绕过它。上一条"不得更改"的三个常量在这里依然有效。
- **"添加账号"必须走 `data/account/AccountAdder`**（2026-09-19）：两种来源 —— 粘贴 PST、
  邮箱密码登录 —— **都先过 `/user/data` 验证，再落盘、再切换**。
  早期实现把粘来的 Token 不验证就保存并切换，粘错会把会话带进"已保存但不可用"的状态；
  两条路径都别绕过验证。登录路径的顺序与连接页一致：派生 → `/user/login` → 复验 → 保存，
  登录失败不碰复验接口，复验失败不落盘。有单测钉住（`AccountAdderTest`）。
- `CredentialStore` 接口里的多账号方法带有**默认假实现**（如 `switchAccount` 直接返回 false）——
  不要依赖它们：只有 `KeystoreCredentialStore` 的实现是真的。以后加方法直接写进实现类，
  别再往接口里塞"默认返回失败"的占位实现（调用方会静默失效，且不会有编译错误提醒）。

### 参数记忆

- 生成页的编辑状态由 `GenerateViewModel` 自动持久化到 `GenerationDraftPreferences`，
  启动时恢复。**给 `GenerationParams` 新增字段时，必须同步更新
  `GenerationDraftCodec` 的 DTO**，否则新字段不会被记住，而且不会有编译错误提醒。
- `GenerationDraft`（草稿，可变）与 `Generation`（历史，写入后不可变）刻意分开，
  不要为了省事合并成一个类型。
- 恢复路径必须能逐字段降级：不认识的枚举用默认值、越界数值交给
  `ModelProfile.normalize` 修正，**只有整串 JSON 无法解析时才整体回退**。
  `GenerationDraftCodecTest` 覆盖了这些降级行为，改动时别删。


## 分层约定

`core/` 与 `domain/` 是纯 Kotlin，**不得引入 Android API**。请求构造、提示词解析、参数校验、ZIP 校验这些最容易出错的逻辑都放在这里，好处是能在 JVM 单元测试里直接断言，不需要模拟器。

新增这类逻辑时，同步补单元测试；测试用中文方法名描述行为，断言用 Truth。

## 界面结构（改动前先读）

首页 = 瀑布流画廊 + 生成悬浮层，两者**共用同一个 `GenerateViewModel`**：

- 该 ViewModel 在 `PocketNaiApp` 这一层创建（activity 作用域），再传给 `HomeScreen`。
  **不要把它下移到 HomeScreen 或悬浮层内部**：Tab 会来回切换，
  `saveState`/`restoreState` 下 NavBackStackEntry 的 ViewModelStore 不保证保留，
  用户输入的提示词会丢。
- **生成按钮（`GenerateButton`）放在悬浮层头部右侧，不要移回底部栏或改成自由 FAB。**
  三种方案都试过：全宽底部栏让底部过重（用户反馈）；浮在画廊上的 Free FAB 会被
  展开的悬浮层完全盖住，导致用户刚编辑完参数就找不到生成入口；头部方案同时满足
  「不被滚走、展开收起都可见、不占额外高度」。详见技术决策记录 5.1。
- 悬浮层头部是两行（2026-09-21 界面减负，原来是四行）：第一行标题 + 参数摘要（右对齐），
  第二行由 `SheetStatusLine` 在 未连接 → 生成中 → 失败 → 提示词为空 → 余额 里择一显示 ——
  任何状态都比余额优先（余额不该顶掉状态，余额规划 §11.1），轮到余额时它可点击打开详情。
  不要再把余额拆回独立一行；"上拉展开"教学文案已删，拖把手就是展开 affordance。
- 悬浮层内容区的顺序是**提示词在前、参考图区在最底部**（2026-09-14 用户明确要求）：
  提示词是每次进来都要写的东西，参考图只在需要时用。不要把参考图挪回顶部 —— 那会把提示词压到首屏之外。
- **参考图区是一张带页签的卡片**（`ReferenceTabsSection`，2026-09-21 界面减负）：图生图 /
  Precise / Vibe 三个面板（`Img2ImgPanel` / `PreciseReferencePanel` / `VibeTransferPanel`）
  用分段按钮切换，已挂数量标在页签文字里。**页签不代表互斥** —— 图生图 + Vibe 可同挂，
  互斥清理仍由 ViewModel 在挂图时做；不要给页签加"切换时清空别家"的行为。
- **长段说明文字一律收进 ⓘ**（`ui/common/Controls.kt` 的 `InfoButton`，`SectionHeader`
  带 `infoText` 参数）：说明散文默认不占表单行，需要的人点 ⓘ 看对话框。
  新增"用法解释"时走这里，不要再把整段散文直接排进表单。
- 悬浮层的内部滚动**始终挂载**，收起时用 `verticalScroll(scrollState, enabled = expanded)`
  禁用而已。**不要改回"只在展开时挂载"**（`if (expanded) Modifier.verticalScroll(...)`）：
  手势确实要留给悬浮层（否则手指在表单上往上拖只会滚内容、永远拉不开），但滚动位置也必须
  一直生效 —— 展开动画期间 `SheetState.currentValue` 还是 PartiallyExpanded、`expanded` 仍为 false，
  此时不挂载滚动会让内容先按顶部排版、动画落定才跳到上次的位置，表现为"先看到最上方、
  然后页面忽然跳一下"（2026-09-16 用户报告，技术决策记录 §26）。
- 悬浮层内容的**顶部内边距必须放在滚动外面**（`padding(top = 16.dp)` 在 `verticalScroll` 之前，
  左/右/下留在里面）：收起时 peek 只到头部，peek 比头部高出的那一小条会露出表单顶端 ——
  内边距留在滚动里会跟着滚走，露出"被截断的一行"。放到外面，那一小条永远是空白。
- 画廊顶部是**筛选栏**（关键词搜索 + 筛选入口图标），它属于画廊、不属于悬浮层。
  模型 / 模式 / 仅收藏与多选收在筛选对话框里（2026-09-21 界面减负，早年一排 chip
  在 360dp 里排不下、横滚无可视提示）；图标上的角标只数"藏起来"的条件（不含关键词）。
  对话框里 chip 文案直接是"当前值"（`全部模型`），**不要写成"模型：全部"**。
- 设置页是**账号卡片 + 分组列表**（2026-09-21）：每个设置项一行（标题 + 当前值 + ›），
  点进去是对话框，说明散文只住在对话框里；新增设置项按这个形态加，不要整段铺回页面上。

在模拟器上用 `adb shell input swipe` 验证拖拽时，**手势要慢且距离长**（例如
`input swipe 540 1320 540 200 900`）。太快太短的 swipe 不会触发 Compose 的拖拽识别，
会让人误判为“悬浮层拉不开”。

用 `adb shell input text` 输入带空格的文本时，**空格要写成 `%s`**，并且整串要用引号包住：
`input text '1girl,%ssilver%shair'`。直接写空格只会输入第一个词，
看起来就像应用把用户输入截断了 —— 会白费一轮排查。

长按选中文本可以用 `input swipe x y x y 900`（同一点、长时长）。

截图验证时注意：MuMu 的 `screencap` 偶尔会合成到上一帧，出现并不存在的 UI 状态。
怀疑时连拍两张比对字节数，相同则画面稳定。

模拟器分辨率会在 1080x1920 与 1920x1080 之间变化，`input tap` 前先确认截图尺寸，
不要复用上一次的坐标。

排查数据问题时**不要用 `adb exec-out run-as <pkg> cat databases/pocketnai.db` 直接读主库文件**：
Room 默认启用 WAL，刚写入的行还在 `pocketnai.db-wal` 里，主库里查不到，
会让人误判成"这个功能没落库"（本次就误判过一次）。
要么把 `pocketnai.db` 与 `pocketnai.db-wal`（必要时 `-shm`）一起取回放在同一目录再打开，
要么让应用自己把结果读出来（界面显示 / 日志长度，注意不要打印内容）。

## 在设备上跑测试之前

- **`:app:connectedDebugAndroidTest` 默认会在结束时卸载被测应用**，
  而卸载 = 清空应用数据：本机凭据（Keystore 加密）与全部生成历史一起消失。
  已在 `gradle.properties` 里设 `android.injected.androidTest.leaveApksInstalledAfterRun=true`
  挡住这个默认行为；**但换机器/换 IDE 时先确认这条还在**。
- 仪器化测试里读 Room 的 Flow 用 `first()`，**不要写 `toList().first()`** —— Room 的 Flow
  永不结束，`toList()` 会一直等下去，表现是"测试卡死"。
- 仪器化测试（`app/src/androidTest`）目前只覆盖三件必须依赖 Android API 的事：
  生成结果的裁切与元数据保全、Room 迁移、分辨率控件的文案。
  纯逻辑一律放 JVM 单测，不要往 androidTest 里塞。
- 真机截图验证需要应用里能连上账号。测试机的应用数据被上面那条清过一次
  （2026-09-14），遇到"怎么又要重新连"时先想这件事。

## 待核对清单

不要把"按公开 API 语义整理的值"当成官方默认值。当前仍待核对的项集中在
[docs/PocketNAI-技术决策记录.md](docs/PocketNAI-技术决策记录.md) 第 3.7 节，
改到相关代码时先看一遍。

局部重绘（Inpaint）的调研与当前状态见
[docs/PocketNAI-局部重绘功能规划书.md](docs/PocketNAI-局部重绘功能规划书.md)（§9 是实施记录）
与技术决策记录第十七、十八节。**实现已完成并真机验证通过，四个模型全部开放** ——
早期"服务端拒绝 infill"的结论是模型 ID 用错所致，别再按那条旧结论处理。详见上面"局部重绘（Inpaint）"一节。

参考图功能（Image2Img / Vibe Transfer / Precise Reference）的清单见
[docs/PocketNAI-参考图功能规划书.md](docs/PocketNAI-参考图功能规划书.md) 第 3.4 节。
最大张数（A2）、计费（A4）与**滑块初值**都已核对（2026-09-18，技术决策记录 §30.7）：
img2img Strength 0.7 / Noise 0、Precise Reference 三滑块全 1.0、Vibe 0.6 + 1.0
（官方对 V4.5 Full 的 Information Extracted 用 0.7，我们统一 1.0，差异已写进注释）。
**别再把这些值当"经验值"改回去。**

仍未核对的只剩两项：**导演工具（`/ai/augment-image`）的单价**（前端不计价、文档不给数字，
只能靠一次真实调用后的余额差反推，见 §30.5）与**代理 3 秒超时在真机+海外节点是否偏紧**（§29.3）。

低成本的协议探针技巧：`GET /ai/generate-image/suggest-tags?prompt=x&model=<模型ID>` 会校验模型 ID（无效返回 400），
可以用它零成本核对模型 ID，不必发起真实生成。该端点现在也是**标签补全**的数据来源
（见技术决策记录第八节）：不消耗 Anlas，但匹配是**包含式**而非严格前缀，建议里可能出现与输入无关的词。
