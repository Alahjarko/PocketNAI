# PocketNAI 参考图功能规划书（Image2Img / Vibe Transfer / Precise Reference）

> **状态：待评审，尚未实施。**
>
> 目标：在现有 T2I 链路上增加三种"以图为输入"的生成方式，对应官方网页版 `Reference Images` 分页里的三张卡片。
>
> 编写日期：2026-09-14
> 适用基线：PocketNAI v0.1.0（T2I、收藏提示词、草稿记忆、标签补全已完成；数据库 v3）
> 协议依据：官方 `https://image.novelai.net/docs/doc.json`（OpenAPI，2026-09-14 下载）。
> 该文件保存在本机 `.tooling/novelai-openapi.json`，**不入版本库**（`.tooling/` 已被 gitignore），
> 需要复核时按上面的地址重新下载即可。
> 预期读者：负责实现、测试和验收该功能的开发者或编码助手
> 相关文档：[规划书草案](PocketNAI-规划书草案.md)、[技术决策记录](PocketNAI-技术决策记录.md)

---

## 1. 结论与交付目标

三种功能共用同一个端点 `POST /ai/generate-image`，只在 `parameters` 里增加不同的字段，
因此**不需要新的生成链路**：现有「校验 → 落 Generating 记录 → ZIP 传输 → 解包校验 → 原子写入 → 提交数据库」
这条流程原样复用，改动集中在三处：

1. **参数模型**：`GenerationParams` 之外新增一层「参考图集合」，因为参考图是数量可变、可选的，
   塞不进现在这个扁平结构（它要映射成 `generations` 表的一行）。
2. **请求体**：`NovelAiRequestBuilder` 增加 base64 图片字段与各自的旋钮。
3. **图片管线**：新增「选图 → 解码 → 缩放/加边 → 编码 base64」这一段，现有代码里完全没有对应能力。

交付后用户可以在生成悬浮层里：

- **Image2Img**：选一张图作为起点，用 Strength / Noise 控制改动幅度；
- **Vibe Transfer**：挂上若干张参考图，用 Information Extracted / Reference Strength 控制"取多少味道"；
- **Precise Reference**：为角色或画风挂参考图，额外有 Fidelity 滑块与 character / character&style 两种取用方式。

三种功能都要求生成结果进入现有历史、画廊、详情页与"复用参数"，**不能出现"用参考图生成的历史无法追溯来源"这种情况**。

---

## 2. 范围边界

### 2.1 本次必须完成

| # | 交付项 |
|---|---|
| 1 | 参考图从**系统相册**与**PocketNAI 历史**两个来源导入（相册走 Photo Picker，不申请存储权限） |
| 2 | 图片预处理管线：解码降采样、缩放到合法尺寸、Precise Reference 的黑边补齐 |
| 3 | Image2Img 面板与请求字段（`image`、`strength`、`noise`、`extra_noise_seed`） |
| 4 | Vibe Transfer 面板与请求字段（`reference_image_multiple` 等三个数组），含 encode-vibe 编码与本地缓存 |
| 5 | Precise Reference 面板与请求字段（`director_reference_*` 五个数组） |
| 6 | 参考图本地持久化：数据库 v3 → v4 新增表，图片文件放入私有目录新子目录 |
| 7 | 历史 / 详情 / 复用参数 / 草稿记忆对参考图的完整支持 |
| 8 | 参考图相关的错误分类与用户提示（含体积超限、图片损坏、额度不足） |
| 9 | JVM 单元测试、MockWebServer 契约测试、APK 构建、模拟器安装、界面截图验证 |
| 10 | README、技术决策记录、AGENTS.md 同步 |

### 2.2 本次明确不做

- **不做 Inpaint 与蒙版编辑器**。官方 `Image2Img` 卡片右侧的铅笔图标进入的是局部重绘，
  需要画笔、橡皮、图层与撤销栈，属于首版规划书 2.3 明确排除的 Canvas 子系统；
  OpenAPI 里 `parameters.img2img` 这个嵌套对象也标注为 `used by inpaint`。
  **建议单独立项**，本规划只做整图 img2img。
- 不做 Enhance / Upscale / Director Tools（`/ai/augment-image`、`/ai/upscale` 依旧不在范围内）。
- 不做 ControlNet（`controlnet_model` / `controlnet_condition` 字段继续不发）。
- 不做多角色 Prompt 的 `char_captions` 与坐标框选。Precise Reference **不是**多角色 Prompt：
  它通过 `director_reference_*` 表达，不写 `char_captions`。
- 不做参考图的云端同步、分享或导出（参考图只留在本机与 NovelAI 之间）。
- 不做 Anlas 费用预估（沿用首版规划书 2.3 的结论，额度不足由服务端 402 告知）。
- 不做无人值守或批量自动生成。
- 不修改 `generations` / `generated_images` 的既有列语义，不重建这两张表。

### 2.3 与首版规划书的关系

首版规划书 2.3 把 Img2Img、Vibe Transfer、Precise Reference 一并列为"不纳入首版"，
并注明"这些能力不是永久取消，而是从首版范围和首版验证清单中移除；后续应根据实际使用反馈单独规划"。
本规划书就是这份"单独规划"，因此它不修改首版规划书的结论，只在其后追加一个阶段。
官方界面 `Chunks` 分页对应的 Prompt Chunks，本项目已用「收藏提示词」实现（技术决策记录第四节），**无需重复建设**。

---

## 3. 协议事实

以下字段名与约束**直接来自官方 OpenAPI**，不是推测。凡是推测或社区经验，一律在下表标注并在 3.4 汇总待核对。

### 3.1 Image2Img

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `parameters.image` | string | doc.json | base64 编码的图片，img2img 的起点 |
| `parameters.strength` | number | doc.json | 改动幅度；越小越接近原图 |
| `parameters.noise` | number | doc.json | 额外噪声量 |
| `parameters.extra_noise_seed` | integer | doc.json | 附加噪声的 Seed |
| `parameters.add_original_image` | boolean | doc.json | 是否把原图一并参与 |
| `parameters.color_correct` | boolean | doc.json | 颜色校正 |
| `parameters.img2img` | object | doc.json | 嵌套的 `{strength, noise, extra_noise_seed, color_correct}`，**标注为 inpaint 使用** → 本次不发 |

### 3.2 Vibe Transfer

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `parameters.reference_image` | string | doc.json | 单张 vibe 的 base64 |
| `parameters.reference_image_multiple` | array\<string\> | doc.json | 多张 vibe 的 base64 数组 |
| `parameters.reference_information_extracted` | number | doc.json | 单张时的 Information Extracted |
| `parameters.reference_information_extracted_multiple` | array\<number\> | doc.json | 多张时逐张对应，官方滑块的 "Information Extracted" |
| `parameters.reference_strength` | number | doc.json | 单张时的 Reference Strength |
| `parameters.reference_strength_multiple` | array\<number\> | doc.json | 多张时逐张对应，官方滑块的 "Reference Strength" |
| `POST /ai/encode-vibe` | 二进制响应 | doc.json | 请求体 `{image(base64), model, information_extracted(0–1), mask?, crop_to_mask, focus_seed, info_extract_seed}`，返回含 vibes 的二进制文件 |

encode-vibe 的请求体里**带 `model` 字段**，这暗示编码结果可能与模型相关（也可能只是校验用）。
在设计缓存时按"与模型相关"处理，见 3.4 第 5 项。

**关键未决点**：官方网页版的做法是先用 `encode-vibe` 把图片编码成 `.vibe` 二进制，
再把它的 base64 放进 `reference_image_multiple`。但 `reference_image_multiple` 的类型只写了 `string`，
**没有说明它接受的是原始图片 base64 还是已编码的 vibe base64**。这直接决定：
要不要新增一个网络端点、要不要做编码缓存、每次换图会不会多一次往返。见 3.4 第 1 项。

### 3.3 Precise Reference

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `parameters.director_reference_images` | array\<string\> | doc.json | 参考图 base64；**原图说明：1024×1536 或 1536×1024 或 1472×1472，用黑边补齐到该尺寸** |
| `parameters.director_reference_descriptions` | array\<V4ConditionInput\> | doc.json | 每张的说明；**原图说明：`caption.base_caption` 置为 `"character"` 或 `"character&style"`** |
| `parameters.director_reference_strength_values` | array\<number\> | doc.json | 0–1，官方 Strength 滑块 |
| `parameters.director_reference_secondary_strength_values` | array\<number\> | doc.json | 0–1，**原图说明：对应 Fidelity 滑块** |
| `parameters.director_reference_information_extracted` | array\<number\> | doc.json | 0–1 |

`V4ConditionInput` 的结构为 `{caption: {base_caption, char_captions[]}, legacy_uc, use_coords, use_order}`；
Precise Reference 只用到 `caption.base_caption`，`char_captions` 保持空数组（与现有 `v4_prompt` 的构造方式一致）。

### 3.4 待核对清单（按可核对成本排序）

**A 类：零成本，用户可在官方网页版直接观察（实施前先做）**

| # | 待核对项 | 为什么要先确认 |
|---|---|---|
| A1 | 三个面板各自的滑块**默认值**（Strength、Noise、Information Extracted、Reference Strength、Fidelity） | 规划书 3.2 明确"默认值必须记录后固化，不得凭经验猜"，与本项目对 Steps/Guidance 的处理保持一致 |
| A2 | ~~Vibe 与 Precise Reference 的**最大张数**~~ | **已确认（2026-09-14，用户核对官方网页版）：4**。已固化为 `ModelCatalog` 里两个常量 |
| A3 | Image2Img 的铅笔图标是否确实进入 Inpaint，以及 Precise Reference 是否支持"从历史选图" | 决定 4.6 的排除范围是否准确 |
| A4 | 三个功能是否各自有官方标注的 Anlas 加价 | 影响 4.5 的费用提示文案 |

**B 类：需要一次真实请求（消耗 Anlas，必须由用户手动触发）**

| # | 待核对项 | 探针方式 |
|---|---|---|
| B1 | `reference_image_multiple` 接受原始图片 base64 还是 vibe base64 | 用户手动发起一次单张 Vibe 生成；若服务端报参数错误则改为先 `encode-vibe`。**在确认前，代码按 `encode-vibe` 路径实现**（这是官方 UI 的做法，更可能正确） |
| B2 | Image2Img 是否要求 `parameters.width/height` 与 `image` 的实际像素一致 | 客户端**默认按一致处理**（提交前把源图缩放到所选尺寸），这样无论服务端是否校验都不会出错 |
| B3 | `/ai/encode-vibe` 是否消耗 Anlas | 决定它是"可以随选图自动调用"还是"必须用户点按钮才调用"。**在确认前一律按消耗处理** |
| B4 | `strength` / `noise` 的合法区间是否就是 0–1 | 界面滑块范围按 0–1 实现，越界由 `ModelProfile.normalize` 夹取 |
| B5 | encode-vibe 的产物是否**与模型相关**（它的请求体里有 `model` 字段） | 决定缓存键。**默认按相关处理**：缓存键 = `model + 图片 sha256 + information_extracted`，多存几个副本的代价远小于"跨模型复用错的 vibe" |

**C 类：已在阶段 C 的实机验证中解决**

| # | 结论 | 证据 |
|---|---|---|
| C1 | 整图图生图必须发 `action = "img2img"`，只在 `parameters` 里带 `image` 会被拒绝 | 服务端原文：`image is not allowed for regular generations, use img2img or infill`。**这一项原本不在清单里**，是实测补上的 |
| C2 | `strength` 放在 `parameters` 顶层可被接受（不必放进嵌套的 `parameters.img2img`） | 修正 action 后同一次请求即成功 |
| C3 | 图生图的输出尺寸可以正好等于请求里的 `width`/`height`（提交前按该尺寸裁切源图） | 出图 1216×832，与请求一致 |
| C4 | `noise` / `extra_noise_seed` / `add_original_image` / `color_correct` 不发也能成功 | 未发这四项的一次生成成功，说明服务端有可用的默认值 |

**核对纪律**：B 类探针一律**由用户在真实界面上手动发起**，实现过程中不得自动调用；
核对结果写回本文档与 [技术决策记录](PocketNAI-技术决策记录.md)，做法沿用现有的"探针 → 记录 → 固化"流程。

---

## 4. 产品与交互设计

### 4.1 生成悬浮层改成两个分页

官方把 Prompt / Reference Images / Chunks 做成同一层的三个分页。我们的悬浮层现在是一条长表单，
再往里塞三个参考图面板会变成一个需要滚很久的页面，而且"参考图"对多数生成是无用的（默认不挂任何参考图）。

**方案**：在悬浮层头部（标题 / 参数摘要 / 生成按钮）下方、表单上方加一行分页：

```
┌─────────────────────────────────────────┐
│ 生成设置                    [ ✦ 生成 ]   │  ← 头部固定，不参与滚动
│ V4.5 Curated · 832×1216 · I2I · 1 Vibe  │  ← 摘要行带上参考图标记
│ 下滑收起，看生成结果                        │
├─────────────────────────────────────────┤
│   [ 提示词 ]  [ 参考图 ① ]                │  ← 新增分页
├─────────────────────────────────────────┤
│  （提示词分页 = 现在的整条表单）            │
│  （参考图分页 = 三张卡片）                  │
└─────────────────────────────────────────┘
```

设计理由：

- **默认停在「提示词」分页**，参考图不占用任何垂直空间，T2I 用户的操作路径与现在完全一致。
- 分页标签上用角标显示"当前挂了几张参考图"（如 `参考图 ①`），收起悬浮层时也能从摘要行看到，
  避免"忘了自己还挂着参考图，生成的图和提示词对不上"。
- **摘要行必须包含参考图标记**。现在的摘要只有模型 / 尺寸 / 质量标签；img2img 模式下尺寸由源图决定，
  摘要里还要体现这一点（例如 `832×1216（源图）`）。
- 用 M3 的 `PrimaryTabRow`（material3 1.3 已提供）或 `SingleChoiceSegmentedButtonRow`，不引入新依赖。

### 4.2 Image2Img 面板

| 元素 | 行为 |
|---|---|
| 选图 | 两个入口：**从相册**（Photo Picker）/ **从历史记录**（打开画廊选择器） |
| 预览 | 缩略图 + 源图像素尺寸 + 文件体积；可一键移除 |
| Strength | 滑块 0–1（默认值待 A1 核对） |
| Noise | 滑块 0–1（默认值待 A1 核对） |
| 其余 | `add_original_image` / `color_correct` 先按"是否需要暴露给用户"处理：**首版不做开关**，固定发与服务端默认一致的值（待 A1 核对后固化），避免把不确定的字段交给用户 |

**尺寸的联动是本面板最关键的一处**：

- 选定源图后，Resolution 由源图决定：把源图按 64 的倍数缩放到最接近的合法尺寸，
  并在界面上直说"已将源图缩放到 832×1216 后提交"（不能默默改用户选的图）。
- 用户在 img2img 模式下仍然可以改 Resolution，此时**按新尺寸重新缩放源图**，提示同步更新。
- 源图不是合法尺寸（边长非 64 倍数、超出模型上限、单边最大尺寸）时，
  走 `ModelProfile.normalize` 的同一条归一化路径，不新增第二套规则。

### 4.3 Vibe Transfer 面板

- 可添加多张参考图（上限待 A2 核对，代码里用常量集中管理，改一处即可）。
- 每张一个条目，包含缩略图、Information Extracted 滑块、Reference Strength 滑块、移除按钮。
- 每张图在加入时后台编码一次（`encode-vibe`），编码结果按
  **`model + sha256(图片) + information_extracted`** 缓存到本地；再次使用同一张图时直接用缓存，不重复请求。
  （encode-vibe 的请求体带 `model`，因此缓存键必须带上它，见 3.4 B5。）
- 编码中的条目显示进度态；编码失败时该条目标红并提供重试，**不影响**其它参考图与提示词的编辑。
- 若 B1 核对结果是不需要 encode-vibe，则本面板退化为"直接把图片 base64 挂上"，缓存层整块删除 ——
  因此编码缓存要做成**可摘除的一层**，不要让请求构造依赖它。

### 4.4 Precise Reference 面板

- 同样支持多张（上限待 A2），每张条目包含：
  - 缩略图（**显示加黑边后的实际提交图**，而不是用户原图）；
  - 类型选择：`character` / `character&style`（对应 `base_caption` 的两个取值）；
  - Strength 滑块、Fidelity 滑块（`director_reference_secondary_strength_values`）、Information Extracted 滑块。
- **黑边补齐在客户端完成**：把用户图片等比缩放后居中贴到官方要求的画布上
  （1024×1536 / 1536×1024 / 1472×1472 三选一，按原图方向决定），其余区域填黑。
  界面上要显示"已按官方要求补齐为 1472×1472"。
- 这一层与 Image2Img 的"源图"语义完全不同（一个是生成起点，一个是参考条件），
  界面上必须分开呈现，**不允许**共用同一个"选图"状态。

### 4.5 与既有功能的关系

| 既有能力 | 参考图加入后的行为 |
|---|---|
| 历史与画廊 | 每条历史记录能看出用了哪种参考模式；详情页展示参考图缩略图与各自参数 |
| 复用参数（详情页） | **必须一并带回参考图**。只带回 Strength 却没有图，参数没有意义 |
| 草稿记忆 | 只记录参考图的**本地文件路径与参数**，不记录 base64；文件缺失的条目在恢复时静默丢弃 |
| 收藏提示词 | 不受影响；收藏的是文本，不是参考图 |
| 标签补全 | 不受影响 |
| 生成按钮可用性 | img2img 模式下**必须已选源图**才能生成；参考图未编码完成时按钮置灰并说明原因 |
| 费用提示 | 首次进入参考图分页时提示一句"参考图模式会改变本次生成的计费方式，额度不足时服务端会拒绝"；具体金额不做本地计算 |

### 4.6 为什么 Image2Img 先做、Inpaint 不做

`Image2Img` 是三者里唯一不需要新交互范式的能力：它复用了现有的"选图 → 参数 → 生成"骨架，
只是多一个图片来源。Vibe 与 Precise Reference 都需要"多条目 + 逐条参数"的新组件，
所以顺序上先做 Image2Img，把图片管线、存储与迁移一次做对，后面两个只是复用这条管线。

Inpaint 排除的理由在 2.2 已说明：它需要蒙版编辑器，是另一类工作量（Canvas / 画笔 / 撤销栈），
而且 OpenAPI 里专门为它准备了嵌套的 `parameters.img2img` 对象 —— 也就是说它连请求形态都与整图 img2img 不同。

---

## 5. 技术设计

### 5.1 参数模型：在 `GenerationParams` 之外新增"参考图集合"

`GenerationParams` 现在被 `Mappers.toEntity` 映射成 `generations` 表的一行（每个字段一列），
参考图是数量可变、可选的列表，塞进去会破坏这个映射。因此：

```kotlin
// domain/model/GenerationMode.kt
enum class GenerationMode { TXT2IMG, IMG2IMG }

// domain/model/ReferenceImage.kt
enum class ReferenceRole { IMG2IMG, VIBE, DIRECTOR }

data class ReferenceImage(
    val id: String,
    val role: ReferenceRole,
    val ordinal: Int,
    val relativePath: String,       // 私有目录内的相对路径
    val width: Int, val height: Int,
    val byteSize: Long,
    val sha256: String,
    // 逐条参数，按 role 使用其中一部分
    val informationExtracted: Double? = null,
    val strength: Double? = null,
    val secondaryStrength: Double? = null,   // Precise Reference 的 Fidelity
    val captionBase: String? = null,          // "character" / "character&style"
    val vibeCachePath: String? = null,
)

/** 一次生成的完整输入：参数 + 参考图。请求构造的唯一入口。 */
data class GenerationRequest(
    val params: GenerationParams,
    val mode: GenerationMode,
    val references: List<ReferenceImage>,
)
```

配套：

- `ModelProfile` 增加能力查询：`supportsImg2Img` / `supportsVibe` / `supportsDirectorReference`
  与各自的**数量上限、滑块默认值**（数值一律等 A1/A2 核对后再填，未核对前集中在一处并带 `CONFIG_VERSION`）。
- `GenerationParams.migrateTo` 不变；跨模型迁移时若目标模型不支持当前参考模式，**照现有做法产生提示文案让用户确认**，
  不静默丢弃参考图。
- 归一化与校验继续只走 `ModelProfile.normalize` / `validate` 一个出口，参考图相关校验（数量、尺寸、体积）
  加在同一处，避免出现第二套规则。

### 5.2 请求体：`NovelAiRequestBuilder` 的扩展

- `build(profile, params)` 保留（T2I 路径不动），新增 `build(profile, request)` 重载：
  先构造现有 `parameters`，再按 `mode` 与 `references` 追加字段。
- **T2I 的请求体必须逐字节不变**。这一点由现有 `NovelAiRequestBuilderTest` 守住：
  新增重载后，原测试必须继续全绿，且新增一条"空参考图集合的请求体与旧实现完全一致"的断言。
- 参考图 base64 由 `ReferenceImage.relativePath` 指向的文件在**网络层**读取编码，
  不进入 `GenerationParams`，也不进入 JSON 树以外的任何长期结构（避免大字符串被复制到数据库或日志）。
- Precise Reference 的 `director_reference_descriptions` 用现成的 `captionBlock()` 思路构造，
  `char_captions` 保持空数组。

### 5.3 图片管线（全新模块）

放在 `data/image/`，只依赖 Android 平台能力（`BitmapFactory` / `Bitmap` / `android.util.Base64`），**不引入新依赖**。
注意 `ImageDecoder` 需要 API 28，而本项目 minSdk 是 26，因此统一用 `BitmapFactory`（配合 `inSampleSize` 两遍解码）。

| 步骤 | 要点 |
|---|---|
| 选择 | `PickVisualMedia`（`androidx.activity` 已是现有依赖）。**不需要任何存储权限**，也不走 `MANAGE_EXTERNAL_STORAGE` |
| 解码 | 两遍解码：先 `inJustDecodeBounds` 拿原始尺寸，再用 `inSampleSize` 降采样；**单边上限**集中为一个常量 |
| 缩放 | 等比缩放到目标尺寸（img2img 用所选 Resolution；Director 用 1024×1536 / 1536×1024 / 1472×1472 黑边画布） |
| 编码 | 统一输出 PNG；base64 在 IO 线程一次性编码，不缓存整串 |
| 体积闸门 | 编码后超过上限（建议初值 8 MB，集中为常量）时先降采样再编码；仍超限则报 `REFERENCE_TOO_LARGE`，不提交 |
| OOM 防护 | 同时最多解码一张参考图；用 `try/catch OutOfMemoryError` 兜底并映射为 `REFERENCE_DECODE_FAILED` |

纯逻辑部分（目标尺寸计算、黑边画布计算、降采样倍数计算、体积判定）单独抽成不依赖 Android 的对象，
放在 `domain/image/`，这样可以用普通 JVM 测试覆盖边界（极端长宽比、1px 图片、超大图、已是合法尺寸）。
**这是本规划里最应该被测试覆盖的一块**，因为它的错误会直接表现为"提交了服务端拒绝的请求"或"崩溃"。

### 5.4 本地存储与数据库 v3 → v4

**文件**：新增子目录 `files/references/<referenceId>.png`，与 `generations/` 平级。
写入沿用现有的"先落盘、数据库后提交"顺序，并纳入 `cleanupOrphans` 的清理范围 ——
否则用户反复换参考图会留下无人引用的图片文件。

**数据库**：`version = 3` → `4`，只做加法：

```sql
-- 新增表：参考图（数量可变，因此独立成表，与 generated_images 的做法一致）
CREATE TABLE IF NOT EXISTS reference_images (
  id TEXT NOT NULL PRIMARY KEY,
  generationId TEXT NOT NULL,
  role TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  relativePath TEXT NOT NULL,
  width INTEGER NOT NULL, height INTEGER NOT NULL,
  byteSize INTEGER NOT NULL, sha256 TEXT NOT NULL,
  informationExtracted REAL, strength REAL, secondaryStrength REAL,
  captionBase TEXT, vibeCachePath TEXT,
  createdAt INTEGER NOT NULL
);

-- 新增列：生成模式（默认值保证既有记录被正确解读为纯文生图）
ALTER TABLE generations ADD COLUMN mode TEXT NOT NULL DEFAULT 'TXT2IMG';
```

**红线**（沿用 AGENTS.md 的既有约束）：

- 只允许 `ADD COLUMN` 与 `CREATE TABLE`，**不重建 `generations`**：
  `generated_images` 以 `ON DELETE CASCADE` 引用它，重建表会连带删掉用户的图片记录。
- 迁移必须能在"已有真实历史数据"的设备上通过，并保留全部记录（沿用 v1→v2、v2→v3 的验证方式：
  迁移前后点数、图片数、缩略图可用性一致）。
- `Mappers` 是领域模型与实体之间的唯一转换点，参考图的映射加在这里。

### 5.5 网络层

- `NovelAiApi` 增加 `encodeVibe(token, model, imageBase64, informationExtracted): Outcome<ByteArray>`，
  与既有方法一样返回 `Outcome`，不改生成链路的方法签名。
- encode-vibe 走**派生客户端**（与登录同样的做法）并把整体超时收紧，理由相同：
  它是一次短请求，不该继承生成用的 5 分钟读超时。
- 响应体积设上限（沿用 Auth API 里"读到上限即拒绝"的循环写法），超出即判失败。
- 生成的每个 HTTP 层失败继续走 `NovelAiErrorMapper`；**参考图特有**的失败（本地解码、体积超限、
  编码失败）用新的 `ErrorCode`，复用现有 `messageRes()` 映射表。

### 5.6 新增错误码

| 错误码 | 触发场景 | 用户可见文案方向 |
|---|---|---|
| `REFERENCE_DECODE_FAILED` | 图片损坏、格式不支持、内存不足 | "这张图片无法读取，请换一张" |
| `REFERENCE_TOO_LARGE` | 编码后仍超过体积上限 | "图片过大，已停止提交" |
| `REFERENCE_MISSING` | 参考图文件被系统清理或用户删除了历史目录 | "参考图已不在本机，请重新选择" |
| `VIBE_ENCODE_FAILED` | encode-vibe 失败 | 该条目标红 + 重试，不阻断其它编辑 |

额度不足继续用既有的 `INSUFFICIENT_ANLAS`（服务端 402），不为参考图另造一套计费逻辑。

---

## 6. 分阶段实施计划

每个阶段的完成标准统一为：**单元测试全绿 → 构建 Debug APK → 安装到模拟器 → 涉及界面时截图验证 →
更新文档**。真实生成一律由用户手动触发。

### 阶段 0：先做免费核对（不写代码）

- 用户在官方网页版打开三个面板，记录 A1/A2/A4 的默认值与上限；
- 核对 A3（铅笔图标的行为、Precise Reference 能否从历史选图）；
- 结论写回本文档 3.4 与技术决策记录。

**产出**：一张填好的默认值表。**这是后面所有阶段的输入**，没有它就只能沿用猜的默认值，与规划书 3.2 冲突。

### 阶段 A：图片管线 + 存储 + 迁移（不含任何界面）

**✅ 已完成（2026-09-14）。**

- 新增 `domain/image/`（纯逻辑：目标尺寸、黑边画布、降采样倍数、体积判定）与 `data/image/`（Android 实现）；
- 新增 `ReferenceImage` / `GenerationRequest` / `GenerationMode`；
- 数据库 v3 → v4 与 `Mappers` 扩展；
- 文件目录 `files/references/` 与孤儿清理。

**测试**：纯逻辑边界（极端长宽比、1px、超大图、已合法尺寸、黑边补齐的居中位置）+
迁移测试（在 v3 数据上迁移后记录与图片数量不变）。
**验收**：现有 219 个测试全绿；APK 安装后打开旧历史一切正常。

**实际结果**：262 个单元测试全绿（新增 43 个：图片几何 23 个、参考图校验 11 个、映射往返 9 个）。
迁移在两台模拟器上各验证一次，7 条历史与 7 张图片的 id / 时间 / 标题 / 路径 / 哈希完全一致，
`mode` 全部回填为 `TXT2IMG`。实现细节与设计取舍见[技术决策记录第九节](PocketNAI-技术决策记录.md)。

**与原计划的两处偏差**：

1. 参考图文件用**内容寻址**（`references/<sha256>.png`）而不是原文写的 `references/<referenceId>.png`。
   同一张图被多次使用时只占一份磁盘，代价是文件不能随记录删除，必须在启动清理里按
   "仍被引用的路径集合"回收（已实现）。
2. 参考图张数上限先用 4 作为**临时值**，集中在 `ModelCatalog` 两个常量里，
   等阶段 0 的 A2 核对结果出来再改（见 3.4 与第 8 节）。

### 阶段 B：请求构造 + 网络层

**✅ 已完成（2026-09-14）**，但范围按"本轮只做一张图生图"收窄：**未做 `encodeVibe`**
（它只服务 Vibe Transfer，随阶段 D 一起做）。

- `NovelAiRequestBuilder.build(profile, request, sourceImageBase64)` 重载；
- 新增 4 个参考图错误码与文案；
- ~~`NovelAiApi.encodeVibe`~~ 顺延到阶段 D。

**测试**：逐字段断言三种模式的请求体；**"T2I 请求体与旧实现逐字节一致"**；
MockWebServer 覆盖 encode-vibe 的路径 / 请求体 / 响应上限 / 失败映射。
**验收**：单元测试全绿；**不发起任何真实生成**。

### 阶段 C：Image2Img 界面与链路

**✅ 已完成（2026-09-14）。**

- ~~悬浮层分页~~ → 改为表单顶部的一张卡片（理由见技术决策记录 10.4；分页留到阶段 D/E）；
- Image2Img 卡片：从相册（Photo Picker，无权限）与从历史选图、缩略图、Strength、移除；
- 选定源图后 Resolution 自动切到对应的官方预设（Normal 档位），并在卡片里显示"提交尺寸"；
- 摘要行带"图生图"标记；详情页显示"生成方式"与参考图缩略图及逐条参数；
- 历史 / 详情 / 复用参数 / 草稿对 img2img 的完整支持；
- `GenerationRepository.generate` 接受 `GenerationRequest`。

**测试**：ViewModel 层（选图后尺寸归一化、源图缺失时的降级、生成按钮可用性）+ 截图验证。
**验收**：**由用户手动**用一张自己的图跑通一次图生图，确认结果进入历史并能被复用参数带回。
这是本阶段唯一消耗 Anlas 的一步。

### 阶段 D：Vibe Transfer

**⏸ 未开始**（账号所有者要求先做 Precise Reference）。注意它的模型能力与 Precise Reference 相同：
**目前只有 V4.5 能用**，且价格未确认，因此界面必须显示"费用待确认"。

- 多条目面板与逐条参数；
- encode-vibe 调用与缓存（可摘除的一层）；
- B1 核对：若确认可直接传原始图片 base64，则删掉编码层与缓存。

**验收**：用户手动跑通一次单张与一次多张 Vibe 生成。

### 阶段 E：Precise Reference

**✅ 已完成（2026-09-14）。**

- 面板：最多 4 张，每条独立设置取用方式（角色 / 角色+画风）与三个滑块；
- 黑边补齐在**导入时**完成，缩略图就是提交图，黑板上直接可见；
- 与 Image2Img 的选图状态**互斥**（挂上其中一类会清空另一类，因为连 `action` 都不同）；
- **仅 V4.5 可用**：V5 上说明原因并隐藏入口（账号所有者确认的能力边界）；
- 计费：每张 5 Anlas，已用余额观测确认（407 → 402）。
- 真机验证抓到两个缺陷并修正：导入时漏了黑边补齐、草稿里的参考图被启动清理误删
  （详见技术决策记录第十二节）。

**验收**：用户手动跑通 character 与 character&style 各一次，并观察绑定是否生效。

### 阶段 F：收尾

- README 能力表、技术决策记录新章节、AGENTS.md 的待核对清单；
- 参考图占用空间在设置页的"存储占用"里可见；
- 参考图相关的一次性全量回归（构建 + 测试 + 两台模拟器安装）。

**阶段依赖**：A → B → C → (D ∥ E) → F。D 与 E 之间没有依赖，可以互换顺序；
C 必须在 D/E 之前，因为分页骨架与图片管线由它落地。

---

## 7. 验收清单

- [ ] 现有 219 个单元测试全绿，且新增测试覆盖 5.3 的纯逻辑与 5.4 的迁移
- [ ] T2I 请求体与加入功能前逐字节一致（有专门断言）
- [ ] 数据库从 v3 迁移到 v4 后，既有历史记录与图片**数量与内容不变**
- [ ] 参考图文件在删除生成记录后一并清理；启动清理能回收无引用的参考图
- [ ] 从相册与从历史两个入口都能选到图，且**全程未申请存储权限**
- [ ] Image2Img 提交前的实际尺寸与界面显示一致（含"已缩放"提示）
- [ ] Precise Reference 提交的是补齐黑边后的图，界面显示的就是它
- [ ] 参考图整体缺失时（文件被清）给出明确提示并阻止提交，而不是发出空图片的请求
- [ ] 复用参数能带回参考图；草稿恢复时文件缺失的条目被静默丢弃
- [ ] 全程没有自动发起过消耗 Anlas 的请求；所有真实生成均由用户手动触发
- [ ] 参考图的 base64、文件路径与内容不出现在日志、数据库 JSON 或错误文案里

---

## 8. 风险与开放问题

| 风险 | 影响 | 处理 |
|---|---|---|
| `reference_image_multiple` 的语义未确认（3.4 B1） | 可能整条 Vibe 链路要重做 | 按 encode-vibe 实现，但把它做成可摘除的一层；先核对再动界面 |
| 参考图 base64 让请求体达到数 MB 级 | 上传慢、超时、内存压力 | 体积上限 + 降采样 + 派生客户端收紧超时；单张体积作为常量集中管理 |
| Vibe / Director 的额外 Anlas 费用未知 | 用户可能"点一下花掉不少额度" | 界面明确提示会改变计费；不做本地预估；由 402 如实告知 |
| 参考图长期占用磁盘 | 用户存储被吃满 | 纳入"存储占用"展示与孤儿清理；详情页可删除单条生成记录即可回收 |
| 默认值与上限靠猜 | 与官方观感不一致、可能构造非法请求 | 阶段 0 先核对；未核对的数值集中在 `ModelProfile` 并带 `CONFIG_VERSION` |
| 大图解码导致 OOM | 崩溃 | 两遍解码 + 单张并发 + `OutOfMemoryError` 兜底映射为可读错误 |

**仍需产品决策（本规划书给出建议，但需要你确认）**：

1. **草稿是否记住参考图**：建议记住本地文件路径，重开应用后参考图还在（文件缺失则静默丢弃）。
   代价是"上一次的参考图"会一直留在磁盘上，直到被清理。
2. **参考图上限**：在拿到 A2 的官方数字之前，建议先用 4 作为所有数组的临时上限，集中为常量。
3. **是否暴露 `add_original_image` / `color_correct`**：建议首版不暴露，固定发与服务端默认一致的值，
   等 A1 核对后再决定要不要做成开关。理由是这两个开关的语义我们目前没有可靠依据。
4. **Inpaint 是否要立第二个规划**：建议先完成本规划，再根据你实际使用 img2img 的频率决定。

---

## 9. 安全与隐私约束（沿用既有限制，不因本功能放宽）

- 参考图是用户内容：只存在本机私有目录，除发往 `https://image.novelai.net` 外不外传；
  不写入日志、不进入数据库 JSON、不复制到剪贴板。
- 不新增任何权限。相册访问走 Photo Picker，从历史选图走应用内数据。
- Token 处理方式完全不变（Keystore 密文、日志脱敏）。
- 不读取 `persistent-api-token.txt`，不在命令、日志、测试或代码中暴露真实凭据。
- **不自动发起任何消耗 Anlas 的请求**：包括 img2img、Vibe、Precise Reference 与未知是否计费的 `encode-vibe`。
- 不新增第三方中继、代理或分析 SDK。

---

## 10. 对现有代码的影响评估（不变量）

以下内容**在本规划完成后必须仍然成立**：

- `NovelAiRequestBuilder.build(profile, params)` 的行为与输出逐字节不变；
- `GenerationParams` 的字段与语义不变（参考图不进入它）；
- `generations` / `generated_images` 的既有列语义不变，只新增列与表；
- T2I 的用户路径（提示词分页 → 生成 → 悬浮层收起 → 画廊出图）不变，参考图默认不参与；
- 零自动重试、生成不可取消、先落盘后提交数据库的顺序不变；
- 单主机 `image.novelai.net` 不变，不新增主机常量。
