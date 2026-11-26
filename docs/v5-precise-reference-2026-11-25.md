# NovelAI V5 精确参考与服装参考核对记录（2026-11-25）

## 核对来源（匿名公开前端静态资源，零登录、零付费请求）

2026-11-25 从公开匿名页面 `https://novelai.net/image` 拉取当前 Next.js 静态构建包并核对以下模块：

- `22-cczt97ry1s.js`：模型能力表 `ez(modelId)` 与参考模式常量 `tT`。
- `09aajvaad_e63.js`：参考图模型过滤/归一化函数 `c(refs, model)`（偏移 `3609`）、同款服装联动函数 `g(kind, preferSameOutfit)`，以及请求组装条件（偏移 `12900`）。
- `2s4lj3fycctfh.js`：模块 `589161` 的 97 个分辨率分桶表 `E` 与对数长宽比匹配 `D.f(w, h)`，以及模块 `134691` 的居中覆盖裁切实现 `v(e)`。
- `3phjyktz5p57y.js`：精确参考默认初值、滑块上限与 Anlas 附加费公式（偏移 `1787149`、`1844800`、`2688823`）。

## 协议结论

1. **模型能力差异（V4.5 vs V5）**
   - `nai-diffusion-4-5-curated` / `nai-diffusion-4-5-full`（含 `-inpainting`）：
     `vibetransfer=true`、`characterReferences=true`、`charRefInpainting=true`、`resizeCharacterReferences=true`、`bucketCharacterReferences=false`、`characterReferenceFidelity=true`、`characterReferenceStrengthMax=1`、`outfitReferences=false`。
   - `nai-diffusion-5-curated` / `nai-diffusion-5-full` / `nai-diffusion-5-full-medium`（含 `-inpainting`）：
     `vibetransfer=false`、`characterReferences=true`、`charRefInpainting=false`、`resizeCharacterReferences=false`、`bucketCharacterReferences=true`、`characterReferenceFidelity=false`、`characterReferenceStrengthMax=5`、`outfitReferences=true`。

2. **六个 `base_caption` 取值与模型间归一化**
   - 五个并行请求数组名不变：`director_reference_images`、`director_reference_descriptions`、`director_reference_information_extracted`、`director_reference_strength_values`、`director_reference_secondary_strength_values`。
   - `director_reference_descriptions[].caption.base_caption` 支持六个取值：
     - `character&style`：角色与画风（保持同款服装开启，新导入默认值）
     - `character&style_anyoutfit`：角色与画风（关闭保持同款服装，仅 V5）
     - `character`：仅角色（保持同款服装关闭，选“角色”时的默认值）
     - `character_mainoutfit`：仅角色（开启保持同款服装，仅 V5）
     - `costume`：独立服装参考（仅提取服饰造型，仅 V5）
     - `style`：仅画风
   - 切到不支持 `outfitReferences` 的模型（V4.5）时：`costume` 从激活列表跳过（保留在草稿），`character_mainoutfit` 归一化为 `character`，`character&style_anyoutfit` 归一化为 `character&style`。
   - V5 局部重绘（有蒙版且 `charRefInpainting=false`）时，请求不发送 `director_reference_*`，也不计精确参考附加费。

3. **图片预处理：V5 的 97 分桶裁切 vs V4.5 的三画布补黑边**
   - V4.5 继续使用 `1024×1536`、`1536×1024`、`1472×1472` 等比缩放加黑边（`ImageTransform.DirectorCanvas`）。
   - V5 不补黑边，从官方 97 个 `64` 步长分桶（总像素均 `≤ 1024²`）中按 `|ln(w/h) - ln(bw/bh)|` 最小匹配 `[bw, bh]`，按 `scale = max(bw/w, bh/h)` 缩放至 `(rw, rh)` 后居中裁切 `bw × bh`（`ImageTransform.DirectorBucket`）。
   - App 导入参考图时改为保存未补边的原比例归一化 PNG，提交时再按当前模型分支处理，确保在 V4.5 与 V5 间来回切换时不会把 V4.5 的黑边带进 V5。

4. **滑块与计费**
   - 新导入初值：`strength=1.0`、`fidelity=1.0`、`information_extracted=1.0`、`directorKind=character&style`。
   - V5 下 `Strength` 区间为 `0.0–5.0`，不显示 Fidelity 与 Information Extracted 滑块，请求固定提交 `secondary_strength=0.0`（对应 `Fidelity=1.0`）与 `information_extracted=1.0`；草稿仍完整保留原值以便切回 V4.5 恢复。
   - 计费保持每张激活的精确参考 `+5 × n_samples` Anlas；单张常规尺寸与步数的生成本体仍可由 Opus 权益或非负 V5 免费额度抵扣，附加费照常计算。

## 界面设计落地方案

- 将底层 6 个取值解耦为「四类主用途胶囊（`角色+画风` / `角色` / `服装` / `画风`）+ 上下文联动的『保持同款服装』开关」，避免在窄屏上平铺六个长标签。
- 在 V5 选择 `角色+画风` 或 `角色` 时展示柔和 Tonal 开关行，动态解释开启（连同服饰还原）与关闭（仅固定外貌，可搭配提示词或另一张「服装」参考换装）的效果；选择 `服装` 时提示可搭配关闭同款服装的角色参考完成换装。
- 卡片副标题实时显示源图尺寸与当前模型对应的目标提交尺寸（V5 分桶尺寸 / V4.5 补边画布尺寸）。
