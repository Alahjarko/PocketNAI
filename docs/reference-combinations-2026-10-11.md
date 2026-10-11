# 参考图组合修复（2026-10-11）

用户澄清的组合为「图生图＋Precise Reference」或「图生图＋Vibe」，不要求三者同时启用。

## 核对依据

- [NovelAI Precise Reference 官方文档](https://docs.novelai.net/en/image/precisereference/)明确支持与局部重绘一起使用，同时明确 Vibe 与 Precise 不兼容。2026-10-11 本轮读取。
- 当天此前保存的公开前端 `2952-8caf894fb1bb5206.js`：起点图决定 `img2img` action；`characterReferences` 独立传入公共生成构造，再附加 `director_reference_*` 数组。附加条件仅检查模型能力和蒙版兼容位，不排斥起点图。本轮重新获取网页 HTML 返回 403，源码依据是当天保存的快照，没有将该失败描述为新的在线源码验证。

## 修改

- 图生图和 Precise 双向添加均保留另一类素材；重绘追加 Precise 保留底图与蒙版。更换底图仍作废旧蒙版。
- 主模式按局部重绘、图生图、单独 Precise、文生图判定；Precise 请求字段根据当前模型支持的实际参考条件附加。
- Precise 附加费按启用的 DIRECTOR 数量计算，与主模式解耦；V5 上暂存的 Precise 不发送、不收费。
- Precise/Vibe 仍互斥，并增加请求层冲突校验，防止草稿或历史复用绕过界面限制。未扩展重绘与 Vibe 的组合。
- 页签底色改为各功能的实际启用状态，图生图与 Precise/Vibe 可同时点亮；短下划线表示正在查看的面板。查看空面板不意味着启用，V5 暂存素材不点亮。Precise 页签缩短名称以保留数量显示。

## 验证范围

- 30 项 JVM 单测通过；既有 7 项生成链路设备测试通过。扩展原有 Precise 与重绘链路测试，没有增加测试方法，注册总数仍为 50（30 JVM＋20 设备）。
- 测试采用假 API、隔离偏好、内存 Room 和真实图片编码，核对两种选图顺序、重绘追加、主模式、5/10 Anlas 附加报价、请求字段、草稿/历史保全及切换 V5 后停用。未发出真实生成或 Vibe 编码请求。
- 两个 ADB 地址指向同一 MuMu 实例。首次构建工具误把两个地址并行运行，发生进程冲突；改为单地址直接运行后 7 项通过，不算作两台设备验证。
- 实际 App 通过历史选择器添加普通图片，确认原底图、原蒙版仍在且报价增加；移除蒙版后草稿实际保留 IMG2IMG 和 DIRECTOR 两类。检查后逐字节恢复原草稿，原底图及蒙版哈希一致。
- 本地截图和修改前备份：`.tooling/reference-combo-20261011/`。未发布 GitHub；真实服务端组合出图效果未在本轮验证。

最终界面修正后重新通过30项JVM测试，并完成调试包、优化包构建和覆盖安装。MuMu截图确认图生图与Precise同时点亮，查看空Vibe面板时两项仍亮，仅短下划线移动；见 `device/both-active.png` 与 `device/both-active-view-vibe.png`。安装版本为 `0.1.21-reference-combo` / code21，未发布。
