# OpenAI 图片费用粗估（2026-11-02）

目标是生成前知道大致花费，支持约 $0.05 量级的预算判断，不承诺每次误差小于 $0.05。当前连接仍为官方 Images API；兼容服务商可能有不同费率，本次没有增加服务商配置或人民币汇率。

## 已核对的来源

- [官方生图指南及 GPT Image 2.5 输出计算器](https://developers.openai.com/api/docs/guides/image-generation)
- [标准价格表](https://developers.openai.com/api/docs/pricing)
- [GPT Image 输入说明](https://developers.openai.com/api/docs/guides/images-vision#gpt-image-model-inputs)
- 计算器公开资源：`https://developers.openai.com/_astro/GptImageTokenCalculator.react.B8LRKvrg.js`；本次下载带部署参数 `dpl=dpl_6GeSvH8A9VAZdsRFjL8jNHiLu7yF`，SHA-256 为 `ba1a0cb077c6260835085ea977819929dfede2962ca26b96242a4a69b6dc3388`。

两个 2.5 模型的标准费率相同：文本输入 $5 / 百万 token、图片输入 $8 / 百万 token、图片输出 $30 / 百万 token。直接 `/images/generations`、`/images/edits` 不享受 Responses 图片工具的缓存折扣。三路并发也不等于 Batch API，不套用 Batch 折扣。

指南现有计算器明确支持 GPT Image 2.5，并把 Flare / Sunburst 归为同一组。模型介绍页仍有“GPT Image 2 计算器不估算 2.5”的提醒；本实现使用指南里的 **2.5 专用档位**，没有套用旧 GPT Image 2 档位。官方计算器是估算依据，不是服务端逐次 token 承诺。

## 生成前

纯 Kotlin 入口：`OpenAiImagePricing`。与图片尺寸校验共用 `OpenAiImageParams`，适用当前两个受支持模型。

对显式宽高 W/H 和质量：

1. 长边网格 G：low=16、medium=24、high=48、xhigh=64、max=96。
2. 短边网格 S = round-half-to-even(G / (长边 / 短边))。
3. 输出 token = ceil(G × S × (2,000,000 + W × H) / 4,000,000)。
4. 单张输出金额 = token × 30 / 1,000,000。

保留官方 JavaScript 的浮点运算顺序。部分尺寸会落在半格的微小浮点偏差处；改成整数有理数取整反而不完全一致，例如 944×1536/high 为 1201 token。`roundToInt` 的半数取整也与官方不同。

只包含图片输出的示例：

| 尺寸 | low | medium | high | xhigh | max |
|---|---:|---:|---:|---:|---:|
| 1024×1024 | $0.00588 | $0.01317 | $0.05268 | $0.09366 | $0.21072 |
| 3840×2160 | $0.01113 | $0.02595 | $0.10008 | $0.17790 | $0.40026 |

输入采用明确区分于官方公式的本机预算：文本 token 约为 ceil(UTF-8 字节数 / 3)；每张参考图暂按每张成图 **$0.05** 预留。这不是官方按张定价，也没有实测误差保证。2.5 的参考图输入 token 换算未公开，不能拿视觉模型的 patch/tile 规则或旧 GPT Image 1 的 fidelity 规则冒充。

本次总预算 = 张数 ×（单张输出金额 + 文本粗估 + 参考图预留）。多张时逐张预留输入金额，可能偏高。参考图、长文本、多张任务的不确定性会累积，不应把 $0.05 理解为误差上限。没有历史样本校准，也不为校准自动发请求。

auto 质量按当前尺寸的 low～max 显示区间；auto 尺寸和非法尺寸不报金额，不偷偷按 1024×1024 报价。格式、压缩与背景没有独立公开计价因子，不自行添加折扣。数值运算保留精度，显示通常保留两位，小于一美分显示三位，避免显示免费。

生成表单和原有确认框使用同一个费用组件；参考图预留金额可见，详细假设放在 ⓘ。不会为了粗估额外增加确认步骤或发送网络请求。

## 生成后与历史保存

费用折算 =（文本输入 token × 5 + 图片输入 token × 8 + 图片输出 token × 30）/ 1,000,000。接口 usage 是**整次请求**，绝不再乘输出张数或并发数。返回图片的平均金额仅按实际返回数量除，不能把部分结果的总额当成单张价格再重复计费。

每条生成快照保存提交时的价格版本、粗估和返回张数。完整有效 usage 优先用于“整次请求用量折算”；缺失、负数、矛盾的汇总/明细或未知用量结构保持未知。用量异常不丢弃有效图片，也不发第二次请求。直接文生图在没有明细时可把完整 input_tokens 视为文本；编辑缺少输入分类时不能猜出文本/图片费率分摊。

修复原 `upsertGeneration(IGNORE)` 第二次调用未更新响应快照的问题。通过 `UPDATE generations SET openAiParamsJson=...` 原位补入用量，保留图片和参考图子行。数据仍位于 v12 的 JSON 列，新增字段可缺省，无表结构变更。此前未保存的历史 usage 不能凭空恢复。

生成失败没有有效 usage 时保持未知，不当成零费用；返回 usage 后部分图片无法保存时仍保留整次用量。预估与用量折算都不是支付账单，实际扣费以服务商为准。

## 验证范围

扩展既有 OpenAI JVM 合同测试，以独立执行官方计算器得到的向量核对质量、尺寸、横竖交换及半格边界；覆盖批量预算、auto、未知尺寸/模型、费率快照、有效/缺失/非法 usage 和请求不重试。既有设备集成项目补充用量持久化、子行保全、部分结果与并发费用隔离；测试预算维持 29 JVM + 21 设备 = 50。

本轮29项JVM测试通过，失败/跳过均为0；完成Debug APK构建、AndroidTest Kotlin编译和Layoutlib原生渲染。人工检查普通字号、1.5倍字号及深色费用卡片，自动质量区间与参考图预留正常显示。最终日志保存在本次云机`/workspace/.cloud-setup/openai-pricing/validation-final.log`。

只读取公开文档、运行假 API / MockWebServer 检查，不调用真实生图或消耗额度。云端没有在线 Android 设备；设备集成项目仅编译，Layoutlib 预览不作为真机验收。用户随后授权与生成队列一起发布，签名与正式包校验见[发布记录](release-0.1.27.md)。
