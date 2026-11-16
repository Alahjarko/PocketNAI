# PocketNAI 0.1.43 发布核对

2026-11-16 经用户确认后发布 [build-43](https://github.com/Alahjarko/PocketNAI/releases/tag/build-43)。本版围绕首页创作抽屉与底栏导航做细节物理手感与防误触打磨，并将正式发布签名完整迁入持久 InstaBox 沙箱：

- **消除抽屉触顶前停顿感**：将 `GenerationSheetScaffold` 展开弹簧收尾阈值收紧至 `< 0.8px`（`visibilityThreshold = 0.0003f`）、刚度提升至 `750f`（临界阻尼 `DampingRatioNoBouncy`）并按目标方向钳制脱手初速度；同时将内部表单 `contentExpanded` 切换时机从「触顶瞬间切换」改为「开始上拉即保持展开态」，彻底消除接近顶部最后一截的重组卡顿与停顿感。
- **置顶大行程下拉收起抽屉（防误触）**：当创作抽屉（NovelAI 与 OpenAI 面板）已完全展开且内部滚动位于最顶部（`scrollState.value == 0`）时，无需依赖侧边返回或顶部小横条，直接从面板任意区域（含提示词输入框）向下拉动即可跟手收起抽屉。采用 `NestedScrollConnection.onPostScroll` 交接 + `PointerEventPass.Initial` 屏幕根坐标（`localToRoot`）1:1 跟踪，避免父容器位移造成的局部坐标衰减；前 `16dp` 采用 `0.4x` 微缓冲，慢速下拉须跨过 `max(148dp, 24% 行程)`（越线触发 `tick()` 触觉确认）或满足 `>= max(108dp, 17% 行程)` 且下甩速度 `>= 880dp/s` 才会收起，普通滚动回顶与小幅下滑均不会误触。
- **底栏与侧栏四枚导航按钮触觉反馈**：为底部导航栏 `PocketNaiBottomBar` 与宽屏侧边栏 `PocketNaiNavRail` 的「画廊 / 对话 / 抽卡 / 设置」四枚切换按钮补齐遵循系统触感开关的 `PocketHaptics.tick()` 轻刻度反馈。
- **InstaBox 沙箱内闭环编译与正式签名**：正式发布密钥独立保存在持久沙箱 `/data/home/.android/pocketnai-release.keystore`（`chmod 600`，与沙箱日常 `adb install -r` 测试签名隔离），在 InstaBox 内一次性完成编译、R8 资源收缩与 `apksigner` v2/v3 正式签名。

## 构建与签名核对

- APK：`versionCode=43`、`versionName=0.1.43`；正式附件 `PocketNAI.apk`，13,436,014 字节。
- 实际编译源码：`d60db5317b8fc9ea7e262ae1b98690ea4af3b849`。正式标签 `build-43` 固定到该实际编译提交；后续发布核对文档提交不改变生产 APK 内容。
- 全部编译、JVM 单测（`29/29`）、Android 设备测试（`21/21`，总预算 `50/50`）、R8 资源收缩及 `apksigner` 正式签名均在持久 InstaBox Android 14 沙箱（`android`）内完成；GitHub [校验上架任务 43817953141](https://github.com/Alahjarko/PocketNAI/actions/runs/43817953141) 采用 `publish_sha256` 预签名直传模式，跳过云端签名与 Gradle 构建，仅对沙箱已签 APK 执行 v2/v3 证书指纹校验、版本号/分享入口校验及无签名包非签名 ZIP 条目逐字节比对后直接发布至 `build-43`。
- 无签名包 SHA-256：`130d6f675710b2cff5bd9a103c22ec80bf48f7b0ee144173681f30670e05be28`（13,373,735 字节）。
- 公开 APK SHA-256：`7b75ec53705cc80619b30dc9c4b620f832cd2d87b26eb4497a313e6f38625905`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与公开 `0.1.42` 完全一致。

已核对公开 Release 为 `latest`、非草稿、非预发布，匿名下载公开 `PocketNAI.apk` 与沙箱内已签名包 SHA-256 完全一致；仅清理本次临时草稿与临时输入分支，全部历史正式版本完整保留。沙箱内设备使用沙箱既有测试签名覆盖更新至 `0.1.43`，完整保留原有登录凭据与历史图片，未触发任何真实生成、超分或登录请求。沙箱归档位于 `/data/home/workspace/artifacts/release-0.1.43/`。
