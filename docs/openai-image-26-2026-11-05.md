# GPT Image 2.6 接入（2026-11-05）

新增 `gpt-image-2.6-flare`、`gpt-image-2.6-sunburst`，与原2.5模型共同出现在生图选择和画廊筛选。普通生图与参考图编辑复用一次POST的现有通路；草稿、历史参数、复用、报价和详情识别新模型。旧模型和默认选择保留，不发真实付费请求验证。

## 官方核对

- [11月4日更新记录](https://developers.openai.com/api/docs/changelog)：2.6的两个模型及Images生成/编辑端点。
- [Flare](https://developers.openai.com/api/docs/models/gpt-image-2.6-flare)、[Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)。
- [生图指南](https://developers.openai.com/api/docs/guides/image-generation)：complexity支持low/medium/high，省略时为high；与渲染quality独立，不额外收费。本次UI默认high，值随快照保存；2.5不发送该参数。
- [标准价格](https://developers.openai.com/api/docs/pricing)：两个2.6模型与2.5同为文本输入$5、图片输入$8、图片输出$30/百万token。本机没有按缓存/Batch折扣计算。

本次接入应用已有PNG/JPEG/WebP生成与编辑；未扩展SVG、动画GIF或markup图层。接口模型枚举有部分旧文案，模型ID以已发布模型页与更新记录为准。

## 输出费用核对

下载官方指南当前引用的计算器：`https://developers.openai.com/_astro/GptImageTokenCalculator.react.DcSj7WaD.js?dpl=dpl_DgteCD3SZVzFtb3oqXR9KRELqb21`，SHA-256 `7ad72f5981a8f1f87fcbd0f5ca2a8353834b2a4d1ea058425913b702f28288ea`。

2.6使用既有2.5的网格和尺寸公式，先完成原有ceil，再对low乘4.2、medium乘3.3，最后按JavaScript Math.round对正数取整；high/xhigh/max乘1。网格短边仍使用ties-to-even，两次取整不可合并。

独立执行官方计算函数的输出token向量（依次low/medium/high/xhigh/max）：

| 尺寸 | 2.6输出token |
|---|---|
| 1024×1024 | 823 / 1449 / 1756 / 3122 / 7024 |
| 1536×1024 | 664 / 1132 / 1372 / 2459 / 5488 |
| 3840×2160 | 1558 / 2855 / 3336 / 5930 / 13342 |
| 1024×800 | 571 / 1063 / 1286 / 2256 / 5075 |
| 2880×2880 | 2768 / 4894 / 5930 / 10542 / 23719 |

例如1024方图low的输出部分是$0.02469，另计文本和图片输入。保留既有参考图预算预留与“粗估不是账单”的边界，不将旧历史重新定价。

## 验证范围

扩展已有OpenAI合同测试，覆盖两个2.6模型的生成/编辑端点、complexity、参数快照、旧2.5请求不发送新增字段、官方价格向量及画廊身份。设备集成覆盖2.6草稿/参考图/历史/部分结果与费用快照。测试预算仍为29项JVM与21项设备，共50项；没有真实API调用。

29项JVM与21项MuMu设备测试全部通过；完成Debug和R8优化Release构建及同签名覆盖安装。实际屏幕核对2.6模型菜单、复杂度、费用卡片、1.35倍字体的画廊模型筛选。原草稿、参考图和服务选择在交互验收后恢复。本地版本 `0.1.31-ui-cleanup`，构建号31，不代表新增GitHub正式发布。APK为13,134,132字节，SHA-256 `60d34503fb117311f73c72dd7c6b5dfcd1ad2bdd3c627c14df94187c3e436fef`；签名证书SHA-256仍为 `d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`。日志和屏幕证据位于 `.tooling/ui-reduction-20261105/`。
