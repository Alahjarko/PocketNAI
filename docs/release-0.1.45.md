# PocketNAI 0.1.45 发布核对

2026-11-20 经用户确认后发布 [build-45](https://github.com/Alahjarko/PocketNAI/releases/tag/build-45)。本版完成订阅会员到期日恢复、历史冗余测试基础设施精简、顶部全圆角胶囊搜索与内嵌筛选，以及 2026 视觉现代化与自定义主题色盘改造：

- **自定义主题色盘与圆形扩散切色**：新增 `ThemeAccent`（Android 12+「壁纸取色」+ 靛蓝、樱粉、鼠尾草、暖琥珀、深海青、石墨灰 6 套低饱和浅/深 5 级 `surfaceContainer*` 双色系预设），在 `ColorModeCard` 中提供双色圆形色板选择器并持久化到 `SettingsStore`，点选任意色板圆心触发 `ThemeRevealState` 400ms 圆形扩散切换。
- **顶部全圆角胶囊搜索与内嵌筛选**：新增共用 `PillSearchField`，将画廊与历史选图顶部搜索框升级为全圆角无边框胶囊栏，右侧内嵌筛选图标与生效计数徽章，移除画廊顶部冗余任务入口并补充滚动渐现分割线；收藏夹、绘伴历史、画师库与配方收藏 4 处二级搜索框同步统一。
- **生成面板与参考图区「去线框化」**：升级 `WeightHighlightedTextField`（`16dp` tonal 填充）、`DropdownSelector`（无边框 `14dp` `FilledTonalButton`）与独立角色胶囊入口；`ReferenceTabsSection` 升级为 `20dp` `surfaceContainerLow` 卡片与滑动胶囊分段栏，并保持「图生图 + Precise / Vibe」双态生效圆点同步点亮；抽取共用 `ReferenceSourcePickerRow`，统一三面板空状态双无边框 tonal 选图卡片与追加态紧凑双药丸，同步去线框化重绘入口、Precise 类型标签与 Vibe 归一化按钮。
- **详情页操作区、筛选弹窗与绘伴空状态**：清理详情页标题尾部多余标点，将 Seed 升级为可点击一键复制的迷你药丸，主按钮「复用参数」及 4 项高频操作与「交给绘伴调整」统一升级为带图标的无边框 `16dp` tonal 按钮；筛选弹窗改用 `surfaceContainerLow` 底色搭配无边框 `14dp` `surfaceContainerHighest` 芯片；绘伴空状态升级为带 `AutoAwesome` 徽章的居中 `24dp` tonal 卡片。
- **订阅会员到期时间与测试基础设施精简**：在账户卡片中恢复订阅会员到期时间与剩余时长显示；移除无效截屏调用、Paparazzi 离线预览、基准测试模块与重复迁移用例，保留 `49/50` 核心测试预算（`31 JVM + 18 Android`）。

## 构建与签名核对

- APK：`versionCode=45`、`versionName=0.1.45`；正式附件 `PocketNAI.apk`，13,452,398 字节。
- 实际编译源码：`94a45557f398c2ee6427e31354a93db5c6cc7b6c`。正式标签 `build-45` 固定到该实际编译提交；本发布核对文档提交不改变生产 APK 内容。
- 全部编译、JVM 单测（`31/31`）、Android 设备测试（`18/18`，总预算 `49/50`）、R8 资源收缩及 `apksigner` 正式签名均在持久 Instabox Android 14 沙箱（`android`）内完成；GitHub [校验上架任务 44679209366](https://github.com/Alahjarko/PocketNAI/actions/runs/44679209366) 采用 `publish_sha256` 预签名直传模式，跳过云端签名与 Gradle 构建，仅对沙箱已签 APK 执行 v2/v3 证书指纹校验、版本号/分享入口校验及无签名包非签名 ZIP 条目逐字节比对后直接发布至 `build-45`。
- 无签名包 SHA-256：`9316df3b0d4a0f27e4f30813416b0d0c00b11a9547d71a86a16a690271c43869`（13,390,119 字节）。
- 公开 APK SHA-256：`612b8d22bf3c06f47cb1786305c87eb551c096ffe1ea3185c69337cf2d05e3b9`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与公开 `0.1.44` 完全一致。

已核对公开 Release 为 `Latest`、非草稿、非预发布，匿名下载公开 `PocketNAI.apk` 与沙箱内已签名包 SHA-256 完全一致；仅清理本次临时草稿，全部历史正式版本完整保留。沙箱内设备使用沙箱既有测试签名覆盖更新至 `0.1.45`，完整保留原有登录凭据与历史图片，全程未触发任何真实生成、超分或登录请求；沙箱实例已主动挂起，归档位于 `/data/home/workspace/artifacts/release-0.1.45/`。
