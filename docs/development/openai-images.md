# OpenAI 实验性生图

本文件是[开发约定](../../AGENTS.md)的专题补充。涉及生成时，同时阅读图片、网络、存储、界面及验证专题。

## 入口与范围

设置 → 生图服务切换 NovelAI / OpenAI。OpenAI 使用用户自己的 Platform 密钥与额度，提供 `gpt-image-2.5-flare` 和 `gpt-image-2.5-sunburst`。两种服务各自保留草稿；OpenAI 历史支持复用参数，或通过“继续编辑”把成图作为参考。

第一版覆盖画廊生图和参考图编辑。对话图片工具和画师抽卡仍为 NovelAI 专用；OpenAI 模式隐藏抽卡入口，并阻止对话工具误走 NovelAI。设置页在图片任务执行期间禁止切换服务。

## 参数与请求

- 固定连接 `https://api.openai.com/v1`，不套用NAI公共代理。不记录Authorization、描述或原始服务端错误。
- 无参考图时只发一次 `POST /images/generations`；有参考图时只发一次 `POST /images/edits`，JSON 的 `images[].image_url` 使用PNG data URI。
- 描述原样发送，不经过NAI质量词、权重、宏、负向预设或漫画文字处理。不发送NAI的Seed、采样器、步数、角色或 `input_fidelity`。
- 张数1～10；质量 `low / medium / high / xhigh / max / auto`；格式PNG/JPEG/WebP；压缩仅用于JPEG/WebP；背景 `auto / opaque / transparent`（透明禁止JPEG）；审核 `auto / low`。
- 尺寸支持 `auto`、预设与自定义。宽高为16的倍数，每边≤3840，比例1:3～3:1，总像素655360～8294400。超过2560×1440的像素量标明实验性。
- 尺寸菜单包含3840×2160及2160×3840；自定义用独立宽/高滑条（480～3840，16px步长）、数字输入和交换宽高。拖动一边不暗改另一边，允许暂时输入超出总像素/比例的组合，但就地提示并禁用生成；界面、仓库与HTTP发送共用`OpenAiImageParams.sizeError`校验，参数仍按原`size`字符串持久化和复用。说明与验证见[尺寸控件记录](../resolution-controls-2026-11-02.md)。
- 最多16张参考图；本机单张PNG≤14 MiB、合计≤24 MiB。响应从流中解码，读取上限64 MiB，避免复制整份base64响应造成内存尖峰。
- 禁止自动重试、重定向及断流降级重发。超时、5xx或已收到结果但保存失败，标记结果/计费待确认；已保存的部分结果保留，不补发缺失图片。
- 生成前确认模型、尺寸、质量和张数，并显示粗估总价：输出采用官方 GPT Image 2.5 计算器，文本按 UTF-8 字节数÷3 粗估，每张参考图按每张成图 $0.05 本机预留（非官方按张定价）。auto质量显示low～max区间，auto尺寸不报价。完成后按有效整次usage和冻结费率折算，不再乘张数：文本输入每百万token $5、图片输入$8、图片输出$30（2026-11-02核对）。直接Images API不使用缓存/Batch折扣；无完整有效用量保持未知，不当成免费。本机预算不是账单或误差保证，见[费用粗估规则](../openai-pricing-2026-11-02.md)。

## 存储与并发

- `OpenAiImageSettings` 使用 `pocketnai_openai_images`；密钥用既有 `SecretBox` 和独立Keystore alias加密，不预填或复制，云备份与设备迁移均排除。
- `OpenAiImageViewModel`由`AppContainer`持有，同一服务的手动提交允许并行，仓库最多三项同时处理；每项先写独立生成占位，再等待并发位，生成键和下一份草稿保持可编辑。共享服务占用标记直到最后一项结束才释放，期间仍禁止切换服务/密钥，与NAI和抽卡保持互斥。导航和Activity重建不重发POST；启动恢复每进程一次，新任务等待首次恢复结束。详见[调度记录](../generation-queue-2026-11-02.md)。
- 数据库v12只给 `generations` 添加可空 `openAiParamsJson`，保留既有表和外键。历史存实际OpenAI模型ID与独立快照；兼容旧画廊的旧列不能据此构造NAI请求。
- 响应usage、返回张数和提交时费率/粗估使用同一JSON快照。更新必须用`updateOpenAiSnapshot`，不能重复调用IGNORE插入来更新，也不能REPLACE父记录；部分图片保存失败仍保留整次usage。新JSON字段可缺省，旧记录不会伪造用量。
- 图片原字节保存到现有历史目录，按实际PNG/JPEG/WebP格式存储与导出。详情显示OpenAI参数，不显示无意义的Seed或NAI超分按钮。
- 启动孤儿清理同时保护两种服务的草稿参考图。密钥不得进入数据库、图片、日志、测试夹具或源码。

## 2026-10-31 验证

29项JVM和21项设备测试共50项。新增OpenAI HTTP合同及独立生成/存储测试，移除旧的未知费用占位公式检查和单独Vibe双数组映射检查；没有打包旧测试来降低计数。迁移项目更新为10→12，检查旧历史、图片和聊天保留。

用户授权使用限额密钥，实际界面各触发一次1024×1024、low、单张请求：Flare生成红色纸船；Sunburst以该图为参考把船改成蓝色，背景和构图保留。两张均进入画廊并可打开详情。没有发出真实NovelAI生成。两次没有获得完整可解析费用用量，不报告未经确认的金额，也未为查费用额外生成。

本地证据在 `.tooling/openai-images-20261031/`。测试密钥仅在模拟器Keystore加密保存，验收后从设置移除；临时密钥接入辅助代码不进入交付源码或应用APK。高质量、大尺寸、多张、透明输出和多参考图未额外付费实测。

官方依据：[生图指南](https://developers.openai.com/api/docs/guides/image-generation)、[生成接口](https://developers.openai.com/api/reference/resources/images/methods/generate)、[编辑接口](https://developers.openai.com/api/reference/resources/images/methods/edit)。
