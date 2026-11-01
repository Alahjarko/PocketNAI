# Anlas 统计页改造

## 设计来源与取舍

基于 PocketNAI build-24 / `cc557407053fb392002811fec4c85bca9c02ba7e`。
参考 [RikkaHub](https://github.com/rikkahub/rikkahub/tree/5ad19405c1a14899c69bb92e249a3adb3f65fbbd) 的统计页组织与动效原则：

- `StatsPage.kt`：独立页面、时间范围、总量与均量、趋势、分类排行，使用主题色和卡片分区。
- `StatsVM.kt` / `UsageRecordDAO.kt`：时间按设备本地日期归组，短范围补齐空白日期，全部范围按月展示。
- `DotLoading.kt`：等待中使用轻量呼吸反馈，避免把活动状态当作服务端百分比。
- `RouteActivity.kt`：前进/返回有方向感，预测性返回与普通返回使用同一条导航栈。

本次只借鉴设计与交互组织，自行实现适用于现有 Navigation Compose 2.8.5 的页面和动画，没有迁移 RikkaHub 的 Navigation 3 或复制其实现。

## 数据与统计口径

原数据库并没有删除第 101 条以后的记录；100 条上限在读取查询与旧弹窗。现在改为完整 Flow 查询，不做自动裁剪，也不改变 schema 或既有生成记录。

- 范围：近 7 个本地自然日、近 30 个本地自然日、全部；包括今天，未来日期不计入当前统计。
- 范围内总消耗、操作次数、均次消耗、活跃日期、零消耗记录与类型分布均来自同一批流水。
- 统计本机所有账号的 NovelAI 流水，页面直接标注范围；OpenAI 的货币/token 用量不混进 Anlas。
- 0 只表示未观察到扣减，不能断言免费。订阅额度的变化单独保留；负数遗留值不当作退款。
- 按当前设备时区重新归组，跨日/时区变化每分钟更新；日期头带单日小计，卡片仅显示具体时刻。
- 删除图片不删除流水；清空动作继续要求确认，明确会清空全部账号及所有范围，并处理失败。

## 界面与动效

从设置项或余额详情进入全屏二级统计页，有独立返回按钮，不占用底部主导航。首屏先显示指标，完整流水在下方；长说明收到底部浅色注脚。金额和余额启用 OpenType `tnum`，金额加粗，余额缩小并减淡。支持浅色、深色及系统动态配色，窄屏与宽屏沿用同一数据源。

统计金额和趋势切换有短过渡。统计页返回使用 Navigation 的可随手势推进的过渡，跟随系统预测性返回及取消；Manifest 显式开启回调。生成/抽卡保留既有解码成功后圆环变对勾再揭图的规则，等待文案旁新增呼吸圆点；不改变真实请求、重试或计费行为。

## 验证方式

本轮用户明确禁止安卓模拟器，该约束优先于旧 Windows/MuMu 开发文档。不启动或安装任何安卓模拟器，不自动调用真实图片生成。

- GitHub Actions：`.github/workflows/verify-android.yml`，独立分支构建，产物仅作为 Actions artifact。
- JVM：保留 29 项；以超过百条、跨日期完整统计的回归项目替换较低风险且与既有多片段扫描重叠的连续权重高亮用例，设备项目仍为 21 项，总数 50。
- 统计回归走真实仓库映射与纯逻辑，假 DAO，不联网；核对 153 条存储记录、时区边界、未来日期、零消耗、分类一致性、短范围、补零和清空。
- Layoutlib：可选 `-PrenderStatsPreview=true :app:renderStatsPreview`；直接渲染生产 Compose 页面，输出明暗主题、放大字体、平板与空状态图片。它是无设备的预览产物生成器，不是 Android 模拟器，也没有替代真机运行证明。
- APK：同时编译 Debug、Release 和设备测试 APK；Release 使用既有仓库 Secret 中的签名，并与 build-24 的证书 SHA-256 集合比对。
- 真机上的点击、滚动吸顶、预测性返回及其取消、安装保留数据仍须在可用真机实测；本轮不将编译/渲染描述为这些项目已经通过。

## 最终验证结果

[GitHub Actions #41329655672](https://github.com/Alahjarko/PocketNAI/actions/runs/41329655672) 全部通过，构建提交 `0e5a00eec604ce69b3767be63c0fef0eee54168c`，开发分支为 [feat/anlas-statistics](https://github.com/Alahjarko/PocketNAI/tree/feat/anlas-statistics)。

| 项目 | 结果 |
|---|---|
| JVM 回归 | 29 项通过，0 失败、0 跳过；注册总预算 50/50（29 JVM + 21 设备） |
| 实际 UI 渲染 | 9 张均完成目视检查；最终云端图片与已检查图片逐字节一致 |
| APK | Debug、Release、设备测试 APK 均编译通过 |
| 签名 | `apksigner verify` 通过；与 build-24 的签名证书 SHA-256 集合一致 |
| 版本 | `0.1.25-stats-review`，versionCode 25，仅上传 Actions artifact |
| 模拟器 | 本机、云端均未使用 |
| 真机运行 | 本机没有 Android 设备工具；21 项设备测试未执行，安装保留数据和手势仍未实测 |

Release APK SHA-256：`3f35af0581ae2d3d60379e220cbac33be523f9fbdb785712618131b66866b7d9`。

[本地安装包](../../artifacts/PocketNAI-statistics-review.apk) · [9 张界面预览](../../artifacts/previews/README.md)。

后续真机重点检查：从设置和生成面板余额弹层进入/返回；7/30/全部范围切换；流水吸顶与单日小计；预测性返回手势提交及取消；原生成草稿和历史仍在。
