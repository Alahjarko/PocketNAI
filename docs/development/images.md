# 图片、参考条件与元数据

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

## 参考图的请求形态

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

## 局部重绘（Inpaint）

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

## 自定义分辨率

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

## 图片元数据导入（选图时读参数）

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
- **Characters 随提示词一起导入**（2026-09-19）：解析 `v4_prompt` / `v4_negative_prompt`
  的 `char_captions`，按 `CharacterPrompt` 落成"正/负向词 + 五档站位"。
  三条纪律：超出 `CharacterPrompt.MAX_COUNT`（5）的部分**截断并进 notes**；
  官方 5×5 网格的坐标吸附到最近档位（纵坐标丢弃）时**必须提示"位置按站位还原"**，
  不许静默改写；角色词**绝不拼进基础提示词**冒充导入。导入是**整组替换**当前角色，
  不是与草稿里的角色合并（混在一起会得到谁也没画过的组合）。
- 导入**不自动生成**（同 Seed 同参数很容易再产出一张几乎一样的图，而那要花 Anlas）。
- 元数据是外部输入：读取有限额（单块 1 MiB、解压 1 MiB、总量 2 MiB），
  且**不把 `Comment` 原文写进日志**（AGENTS.md 的安全约束）。

## 流式中间预览（技术决策记录 §24）

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
