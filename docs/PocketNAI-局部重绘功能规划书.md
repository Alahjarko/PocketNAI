# PocketNAI 局部重绘（Inpaint）功能规划书

> **状态：调研完成，待评审，尚未实施。**
>
> 目标：在已有的参考图能力之上增加官方界面里那个"涂抹一块区域、只重画那一块"的功能（官方叫 Inpaint，API 里叫 **infill**）。
>
> 编写日期：2026-09-14
> 适用基线：PocketNAI 已实现 T2I / Image2Img / Precise Reference / Vibe Transfer、余额与费用四态
> 协议依据：官方 `https://image.novelai.net/docs/doc.json`（本机 `.tooling/novelai-openapi.json`）
> 产品依据：官方文档站 `https://docs.novelai.net/en/image/inpaint/`、`.../strengthnoise/`、`.../precisereference/`、`.../vibetransfer/`、`.../editimagecanvas/`
> 相关文档：[规划书草案](PocketNAI-规划书草案.md)、[技术决策记录](PocketNAI-技术决策记录.md)、[参考图功能规划书](PocketNAI-参考图功能规划书.md)

---

## 1. 调研结论（先看这一节）

### 1.1 这个功能的性质与前三者完全不同

Image2Img / Precise Reference / Vibe Transfer 都是"**选一张图**"，局部重绘是"**在图上画**"。
它要引入的是首版规划书明确排除的那套东西（2.3："Canvas、画笔、橡皮、图层和复杂撤销栈"）。
因此这不是"再加一个参考图面板"，而是一块**新的编辑器子系统**。

官方 Canvas 的功能清单（文档站 `editimagecanvas`）包括：画笔、橡皮、填充、选择、套索、
取色、调色板、模糊、克隆图章、笔尖形状与压感、HSV 调整、画布缩放、撤销重做、图层、
以及 3D 模型图层。**我们不打算做这些**，见 §2.2。

### 1.2 已经确认的协议事实

| 项 | 结论 | 来源 |
|---|---|---|
| `action` 取值 | **`infill`** | 服务端自己的报错：`image is not allowed for regular generations, use img2img or infill`（真机实测，见技术决策记录第十二节） |
| `parameters.mask` | base64 编码的蒙版 | OpenAPI：`Base64 encoded mask to be applied to the image` |
| `parameters.image` | base64 编码的底图 | OpenAPI |
| 强度/噪声的位置 | 顶层 `strength` 之外，还有一个嵌套的 `parameters.img2img {strength, noise, extra_noise_seed, color_correct}`，其字段说明写着 **`used by inpaint`** | OpenAPI |
| 计费 | **未确认**（官方只公布了 Precise Reference 的 +5） | —— |

### 1.3 已经确认的产品语义（官方文档原文）

- "you'll be drawing one or more blue-tinted selection areas over the image, called a **Mask**, that will determine what portions of the image you want redone. **Anything that is not marked by the Mask, will remain the same.**"
- "You don't need to generate an AI image to use as a base for Inpaint. You can use it on **any uploaded image**."
- 入口："above any generated image, or straight from the Image2Image UI" —— **两个入口**。
- 官方提示：**"Careful if drawing the Mask's edge too close to what you don't want changed, as little pixels might sometimes 'still be seen' by the AI. If you feel like stuff from outside the Masked area is 'leaking' into it, try expanding the Mask."**
  → 这条直接决定了我们要做"**蒙版扩张（膨胀）**"这个控件，它比任何画笔美化都实用。
- 提示词用法："you can prompt for what's already in the image if you just want to reimagine it, or prompt for completely new things"。

### 1.4 顺带核对了三件之前挂着的事

| # | 之前的猜测 | 官方文档的结论 |
|---|---|---|
| 1 | Precise Reference"每张 +5 Anlas"来自账号所有者口述 | **官方文档证实**：*"Using Precise Reference will apply an additional cost of 5 Anlas to each image generation. This extra cost scales with the number of references you use."* 我们的实现（每张 5、按张累加）正确 |
| 2 | Precise Reference 只有 `character` / `character&style` 两种 | **漏了一种**。官方有 **Character Reference / Style Reference / Character & Style Reference** 三种。我们目前只提供"角色"和"角色+画风"，缺**纯画风**那一种 |
| 3 | Vibe 的两个滑块只能凭感觉调 | 官方给出经验值：**所有 vibe 的 Reference Strength 加起来应 ≤ 1.0**，并且网页端有一个 **Normalize Reference Strengths** 开关（V4 及以上）自动归一化 |

第 2、3 条与局部重绘无关，但都是**几行代码就能补上的真实功能缺口**，建议顺手做掉（见 §7 阶段 0）。

---

## 2. 范围边界

### 2.1 本次必须完成

1. 从**画廊/详情页**与**参考图卡片**两个入口进入局部重绘；
2. 一个够用的蒙版编辑器：画笔、橡皮、笔刷大小、撤销/重做、清空、蒙版扩张；
3. 蒙版可视化：半透明叠加显示（能看清底图）、与"只重画被涂抹区域"的直白说明；
4. 请求构造：`action = infill`、`image` + `mask` 的 base64；
5. 底图与蒙版的本地持久化（含"复用参数"要能把蒙版一起带回来）；
6. 历史的详情页能看出"这次是局部重绘"，并能看到底图 + 蒙版缩略图；
7. 费用：未确认价格 → 一律"费用待确认"，进按钮；
8. 单元测试、MockWebServer 契约测试、构建、两台模拟器安装、截图验证；
9. 文档：技术决策记录、README、AGENTS、本规划书的实施记录。

### 2.2 本次明确不做（并且要说清为什么）

| 不做 | 理由 |
|---|---|
| 填充 / 选择 / 套索 / 取色 / 模糊 / 克隆图章 | 这些是"编辑图片"的工具，不是"指定重画区域"的工具。用户完全可以在系统相册里先编辑好再导入 |
| 图层、3D 模型图层、HSV 调整、画布缩放与旋转 | 官方 Canvas 是通用绘图应用；我们的目标是"涂一块出来重画"。每加一个工具都要配套撤销语义与测试 |
| 笔尖形状、压感、笔刷纹理 | 蒙版只有"涂了/没涂"两种状态，笔尖形状不影响结果（只影响手感）。压感需要手势压力 API，收益低 |
| 生成结果的局部对比 / 迭代重绘（在结果上继续涂） | 第一版先把"涂一次 → 生成一次"做稳；迭代重绘是它的自然延伸，但要先确认第一版的蒙版坐标在各尺寸下都对 |
| Enhance / Upscale | 官方文档明确提到"Strength 与 Noise 都拉到最低可以得到原图的完美复制，从而用 Enhance / Upscale"——那是另外两个功能，不在本次范围 |

### 2.3 与首版规划书的关系

首版规划书 2.3 把 Inpaint 排除在外，理由是"Canvas、画笔、橡皮、图层和复杂撤销栈"。
本规划把其中**不可再省的那一小块**（画笔 + 橡皮 + 撤销 + 蒙版扩张）单独立项，
其余仍然排除。这是"把一个大功能切成能验收的一小片"，不是推翻原结论。

---

## 3. 待核对清单（实施前/中必须解决）

### A 类：零成本，用户可在官方网页直接观察

| # | 待核对项 | 为什么重要 |
|---|---|---|
| A1 | **V5 是否支持 Inpaint** | 官方文档没写模型限制。Vibe/Precise Reference 都是 V4.5 专属，Inpaint 很可能 V4.5/V5 都支持（它本质是图生图的变体），但不能猜：猜错会让 V5 用户看到必然失败的功能 |
| A2 | 蒙版是否随 Resolution 一起缩放（即"涂抹时的画布"与"提交尺寸"的关系） | 决定编辑器要不要锁定输出尺寸，见 §5.3 |
| A3 | 官方面板的 **Strength / Noise 默认值**（Inpaint 用不用图生图那两个滑块） | 与之前几步一样：默认值必须记录后固化 |
| A4 | 官方是否在 Inpaint 时也暴露 Quality Tags / Undesired Content | 影响面板的取舍 |

### B 类：需要一次真实请求（消耗 Anlas，必须由用户手动触发）

| # | 待核对项 | 探针方式 |
|---|---|---|
| **B1** | **蒙版的编码约定**：涂抹区域是**白色**还是**透明**？（灰度 PNG？RGBA 带 alpha？） | 这是本功能最大的未知量。设计了一次判别性探针：蒙版只涂左半边，提示词换成完全不同的内容；**若左半边变了 → 白色=重画区域；若右半边变了 → 约定相反**。一次生成即可判定，且从输出图直接能看出来 |
| B2 | `strength` / `noise` 放顶层还是放嵌套的 `parameters.img2img` | OpenAPI 的 `used by inpaint` 指向嵌套对象，但顶层同名参数也存在。先按嵌套发，服务端若报错或行为不符再换顶层 |
| B3 | `add_original_image` 在 infill 里的作用 | 名字像是"把原图合成回去"。先不发（与 `noise` 等字段一致：默认值未知就不发），若发现未涂抹区域有变化再试它 |
| B4 | Inpaint 的计费 | 观测余额差值。注意官方免费条件要求"无基础图片"，因此它**很可能收费** |
| B5 | 蒙版尺寸与 `image` 尺寸不一致时的行为 | 我们的实现保证两者一律同尺寸，因此只是记录现象，不影响实现 |

**核对纪律**：B 类探针一律由用户在真实界面上手动发起；实现过程中不得自动调用。

---

## 4. 产品与交互设计

### 4.1 两个入口

1. **详情页**：给已有图片加一个「局部重绘」按钮 —— 这是官方文档说的"above any generated image"。
   点它 → 把该图作为底图进入生成页的局部重绘模式。
2. **参考图卡片**：选好起点图后，在卡片上出现「涂抹要重画的区域」按钮（官方文档说的 i2i UI 入口）。

两个入口最终都落到同一个状态：**底图已就绪 + 打开蒙版编辑器**。

### 4.2 编辑器（这是本功能的主体）

```
┌──────────────────────────────────────┐
│ 局部重绘                        [完成]│
├──────────────────────────────────────┤
│                                      │
│        底图（按输出尺寸裁切后）        │
│        + 半透明蒙版叠加                │
│                                      │
├──────────────────────────────────────┤
│ [画笔] [橡皮]      笔刷 ▁▁▁▁ 48px     │
│ 蒙版扩张  ▁▁▁ 0px    [撤销][重做][清空]│
├──────────────────────────────────────┤
│ 只重画被涂抹的区域，其余部分保持不变。  │
└──────────────────────────────────────┘
```

刻意的设计：

- **全屏编辑器而不是塞进悬浮层**：涂改需要精度，悬浮层里那点高度涂不准。
  用全屏页面（与详情页同级的路由），保留返回与完成两个出口。
- **蒙版用半透明高亮 + 底图可辨**：官方是蓝色半透明，我们跟随（用主题色即可）。
- **蒙版扩张（0–16px）**：直接对应官方那句"涂得太靠边会让边界像素泄漏，把蒙版扩大一些"。
  实现只是对蒙版做一次形态学膨胀，成本极低但解决真实痛点。
- **只保留"画笔/橡皮"两个工具**：蒙版只有两种状态，多一个工具就多一套撤销语义。
- **撤销/重做按"笔画"而不是按位图快照**：一笔一个记录（含是否橡皮、半径、点列），
  撤销时从头重放。位图快照在 1216×832 下每张 1 MB 起，二十步就是几十 MB。
- **清空需要二次确认**，因为它是唯一不可撤销的破坏性操作（撤销栈不清空的话其实还能撤）。

### 4.3 生成页的局部重绘状态

- 参考图卡片显示底图缩略图 + 「已涂抹区域」的缩略图预览 + 「编辑蒙版」按钮；
- Resolution：**锁定**（见 §5.3）；
- 摘要行加 `重绘` 标记；
- 费用：`费用待确认`（价格未确认）；
- 没有涂抹任何区域时**不允许生成**（发了空蒙版等于整图重画，语义不明）。

---

## 5. 技术设计

### 5.1 请求构造

```json
{
  "input": "<提示词>",
  "model": "nai-diffusion-4-5-curated",
  "action": "infill",
  "parameters": {
    ...现有一切字段不变...,
    "image": "<底图 base64>",
    "mask": "<蒙版 base64>",
    "img2img": { "strength": 0.7, "noise": 0.0, "color_correct": false,
                 "extra_noise_seed": 0 }
  }
}
```

实现落在 `NovelAiRequestBuilder` 的同一个 `build(...)` 里：新增 `GenerationMode.INPAINT`，
按模式决定 `action`（`generate` / `img2img` / `infill`）与要追加的字段。
**T2I / 图生图 / Precise Reference / Vibe 的请求体必须逐字节不变**，由现有断言守住。

**`noise` / `color_correct` 依然不发**（默认值未知）。嵌套对象里只发 `strength`。

### 5.2 蒙版编码：约定做成一个可翻转的函数

与 encode-vibe 那次一样，把不确定性关进一个函数：

```kotlin
/** 涂抹区域在蒙版里怎么表示。B1 探针确认后改这一处。 */
enum class MaskConvention { WHITE_IS_MASK, TRANSPARENT_IS_MASK }
fun renderMask(strokes: List<MaskStroke>, size: PixelSize, convention: MaskConvention): ByteArray
```

- 蒙版一律**与原图同尺寸**、一律**硬边（关闭抗锯齿）**：蒙版是"涂了/没涂"的二值语义，
  抗锯齿产生的半透明像素在服务端会被怎么解释是未知的；
- 输出 PNG（灰度或带 alpha 视 B1 结论），base64 后放进 `parameters.mask`。

### 5.3 尺寸与蒙版的关系（最容易出错的点）

这是本项目在 Precise Reference 上已经吃过一次亏的地方（那时忘了补齐黑边），因此这里提前定死：

**底图在"进入局部重绘"时就被裁切成当前输出尺寸并落盘**，编辑器显示的就是它。
于是"用户涂的坐标"与"提交的像素"严格一一对应，不存在"我涂在这儿、它改到那儿"。

代价是输出尺寸不能再随手改：**局部重绘模式下 Resolution 锁定**，界面说明
"尺寸由底图决定，要改尺寸请重新选图"。这比"改了尺寸蒙版悄悄错位"好得多。

（另一条路是把蒙版按源图分辨率存、提交时用同一个 `ImagePlacement` 以最近邻重采样。
它能保住改尺寸的自由，但多一层间接，而且一旦有人用了带插值的缩放就会毁掉二值语义。
第一版选简单且不会错的那条。）

### 5.4 存储：用一个新的参考图角色，不动数据库 schema

蒙版必须持久化（否则"复用参数"带回底图却没有蒙版，语义残缺），而且**必须被启动清理保护**。

- 新增 `ReferenceRole.INPAINT_MASK`，把蒙版当作一条参考图行存下来；
- 好处：**零 schema 变更**（不再多一次迁移与验证），而且 `GenerationDao.allReferencePaths()`
  的 UNION 查询与 `LiveReferencePathsProvider` 自动把它算进存活集合 —— 这正是我们上次踩过的坑；
- `ReferenceImage` 增加 `maskRelativePath` 不需要：**蒙版自己就是一行**，
  与底图行通过 `ordinal`/顺序配对（一条 INPAINT 底图配一条 INPAINT_MASK）；
- 蒙版文件同样内容寻址（`references/<sha256>.png`），同一张蒙版重复使用只占一份。

（备选方案是在 `reference_images` 上加一列 `maskRelativePath`（迁移 v4→v5），
语义更紧，但要重新验证迁移。第一版不走这条。）

### 5.5 编辑器实现要点

| 关注点 | 做法 |
|---|---|
| 存储 | 蒙版位图用 `Bitmap.Config.ALPHA_8`（1216×832 = 1 MB），只保存 alpha |
| 绘制 | `Canvas.drawPath` + `StrokeCap.Round` / `StrokeJoin.Round`，画笔 `PorterDuff.SRC_OVER`、橡皮 `BlendMode.CLEAR` |
| 坐标 | 视图按 `ContentScale.Fit` 显示，触摸点要**反向换算**回位图坐标（这是本模块最容易写错的地方，必须有单元测试覆盖换算函数） |
| 撤销 | 笔画列表（`isErase`、`radius`、`points`），撤销即重放；重放成本可接受（十几笔） |
| 扩张 | 形态学膨胀：对 ALPHA_8 位图按半径做一次圆形核卷积。纯 Kotlin 实现，可单元测试（在 JVM 上用小位图验证边界） |
| 导出 | 按 §5.2 渲染成 PNG → base64。只在提交时做 |

`MaskGeometry`（视图坐标 ↔ 位图坐标的换算、笔刷半径换算）**做成纯函数放 domain**，可 JVM 测试；
编辑器本身（Compose + Canvas）保持薄。

### 5.6 费用

- 价格未确认 → `GenerationKind.INPAINT`（新增一个枚举值）→ 计算器返回"费用待确认"；
- 不做任何本地猜测。B4 观测到结果后再决定是否加规则。

---

## 6. 分阶段实施计划

每阶段完成标准：单元测试全绿 → 构建 Debug APK → 安装到 MuMu 与 `emulator-5558` → 涉及界面时截图验证。

### 阶段 0：先做免费核对与顺手补缺（不写大代码）

1. 用户核对 A1–A4（V5 是否支持、蒙版与尺寸的关系、默认值、面板取舍）；
2. **顺手补两个已确认的缺口**（几行代码）：
   - Precise Reference 补上"**纯画风**（Style Reference）"这一种取用方式；
   - Vibe 面板加一句官方经验提示"所有 vibe 的 Strength 建议不超过 1.0"，并提供一个
     "归一化"按钮（把当前几张的 Strength 按比例缩放到合计 1.0）。
3. 结论写回本文档与技术决策记录。

### 阶段 A：蒙版几何与编码（纯逻辑，无界面）

- `domain/inpaint/`：视图↔位图坐标换算、笔刷半径换算、形态学膨胀、蒙版渲染成 PNG 的决策函数；
- `MaskConvention` 常量 + 渲染函数；
- 单元测试：换算的边界（缩放比、平移、极端长宽比）、膨胀的正确性、蒙版为全空/全满时的行为。

### 阶段 B：请求构造 + 模式

- `GenerationMode.INPAINT`、`ReferenceRole.INPAINT_MASK`；
- `NovelAiRequestBuilder` 按模式决定 `action` 与字段；
- MockWebServer 契约测试 + "其它模式的请求体逐字节不变"断言。

### 阶段 C：蒙版编辑器（界面）

- 全屏编辑器路由：画笔/橡皮/笔刷大小/撤销/重做/清空/扩张；
- 底图裁切落盘（§5.3）与 Resolution 锁定；
- 截图验证：两种分辨率方向、涂改后蒙版叠加是否清晰、撤销是否按笔画回退。

### 阶段 D：链路打通与历史

- 详情页「局部重绘」入口、参考图卡片入口；
- 历史的 mode/参考图行/详情页展示；
- 复用参数带回底图与蒙版；草稿持久化。

### 阶段 E：真机验证（含 B 类探针）

1. **B1 蒙版约定探针**：半张蒙版 + 完全不同的提示词，一次生成判定约定；
2. B2 强度字段位置、B3 `add_original_image`、B4 计费（余额观测）；
3. 端到端：入口 → 涂抹 → 生成 → 历史 → 复用参数 → 再生成；
4. 把结论写回文档，并把 `MaskConvention` 落到确认值。

**阶段依赖**：0 → A → B → C → D → E。C 依赖 A（几何换算先有测试）。

---

## 7. 验收清单

- [ ] 现有 376 个单元测试全绿，且新增测试覆盖蒙版几何、膨胀、请求构造
- [ ] T2I / 图生图 / Precise Reference / Vibe 的请求体逐字节不变（有断言）
- [ ] 涂抹区域与提交的蒙版**像素级对应**（用一张非对称测试图验证：涂左上角，生成后只有左上角变化）
- [ ] 未涂抹区域在输出图里保持不变（这是本功能的定义，必须亲眼确认）
- [ ] 空蒙版不允许提交，并给出明确说明
- [ ] 撤销按笔画回退，重做能恢复
- [ ] 蒙版文件被启动清理保护（选图 → 重启应用 → 蒙版仍在）
- [ ] 复用参数能带回底图与蒙版
- [ ] 费用显示"待确认"，且**不因算不出费用而禁用生成**
- [ ] 全程没有自动发起过消耗 Anlas 的请求；B 类探针由用户手动触发
- [ ] 蒙版内容不出现在日志里（与参考图同一口径：只记录尺寸与哈希）

---

## 8. 风险与开放问题

| 风险 | 影响 | 处理 |
|---|---|---|
| 蒙版约定未确认（B1） | 万一推错，功能表现为"重画了不该动的区域"，用户会以为功能坏了 | 关进一个枚举 + 一次判别性探针；探针从输出图一眼可判 |
| 输出尺寸被锁死 | 用户想改尺寸就得重新选图 | 明确写进界面说明。若后续确认蒙版可随尺寸缩放，再把锁去掉（§5.3 的备选方案） |
| 编辑器手感（精度/延迟） | 涂不准等于功能不可用 | 全屏编辑；笔画级重放而不是每帧重建位图；两种分辨率方向都截图验证 |
| 计费未知 | 用户可能花钱 | 一律"费用待确认"；探针由用户手动发起 |
| 底图过大时的内存 | 编辑器 + 蒙版 + 底图三张位图 | 底图进入重绘前先按输出尺寸裁切（这一步本来就要做），单张上限沿用参考图管线的降采样 |

**需要产品决策（我的建议）**：

1. **入口放在哪儿**：建议详情页 + 参考图卡片两个都做（官方也是两个），
   但如果你希望先小步，只做详情页入口也够用。
2. **清空是否需要二次确认**：建议要（唯一不可撤销的破坏性操作）。
3. **要不要做"扩张"滑块的实时预览**：建议要（调数值时立刻看到蒙版变胖），
   否则这个参数很难用 —— 官方那句提示的价值全靠它体现。
4. **阶段 0 的两处补缺是否现在做**：建议做，都是几行代码，
   而且是官方文档刚确认过的、用户可感知的缺口。


---

## 9. 实施记录（2026-09-14）

阶段 0–E 已实现，逐项结果与偏离见[技术决策记录第十五节](PocketNAI-技术决策记录.md)。

### 9.1 已完成

- 阶段 0：Precise Reference 补"纯画风"、Vibe 加"强度合计"提示与归一化按钮；
- 阶段 A：蒙版几何（12 个单元测试，含视图↔位图换算的边界与扩张语义）；
- 阶段 B：`action=infill`、`image`+`mask`、嵌套 `img2img.strength`（14 个测试）；
- 阶段 C：蒙版渲染落盘（硬边、内容寻址、约定可翻转）；
- 阶段 D：全屏编辑器（画笔/橡皮/笔刷大小/撤销重做/清空确认/扩张实时预览），已截图验证；
- 阶段 E：三个入口（画廊长按 / 详情页 / 参考图卡片）+ 历史与复用参数的存储路径。

### 9.2 与原计划的两处偏离

1. **蒙版不做形态学膨胀**：发现"圆头笔刷 + 半径整体加 N"与形态学膨胀数学等价，
   于是扩张变成一次半径偏移，实时预览不需要重算位图（原计划写的是做一次圆形核卷积）。
2. **能力位按模型档位而不是按"家族"**：原计划认为重绘属于 Image2Img 家族、四个模型都能用；
   实测被服务端否定（Curated 不支持 infill），改为**只有 Full 档位支持**。

### 9.3 尚未验证（都需要一次非免费请求，只能由用户触发）

1. **Full 档位是否真的支持 infill** —— 账号的免费组合只有 V4.5 Curated，换 Full 就会扣费；
2. **蒙版约定**（B1 探针）—— 因为生成一直没成功，探针尚未执行；
3. Full 档位下的实际计费（预期与图生图同价，但未观测）。

在此之前，`MaskConvention.CURRENT` 取 `PAINTED_IS_WHITE`（SD 系常见约定），
`supportsInpaint` 对 Full 返回 true。两处都是一行常量。
