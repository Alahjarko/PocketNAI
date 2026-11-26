# PocketNAI 1.1.0 发布核对

2026-11-26 经用户确认后发布 [build-54](https://github.com/Alahjarko/PocketNAI/releases/tag/build-54)。本版完整接入 NovelAI V5 官方精确参考（角色 / 服装 / 画风）与 97 分桶无黑边预处理，并将抽卡实验室内置画师词库扩充至 251 位：

- **NovelAI V5 精确参考与服装参考**：开放 `V5 Curated` 与 `V5 Full`（含 Medium Effort）的 Precise Reference（上限 6 张），对齐官网六种 `base_caption`（`character&style`、`character&style_anyoutfit`、`character`、`character_mainoutfit`、`costume`、`style`）、`0–5` 强度区间、97 个分辨率分桶居中覆盖裁切，以及 V5 局部重绘自动停用免附加费逻辑；卡片采用「四类主用途胶囊 + V5『保持同款服装』联动开关」两层交互，详情页与实验性对话生图工具同步适配。详细核对见 [V5 精确参考记录](v5-precise-reference-2026-11-25.md)。
- **内置画师库扩充至 251 位（+28 位现代名家）**：依据用户实机筛选偏好，对 44 位有效候选的 176 张公开作品逐张审片排雷，新增 `piromizu`、`ikeuchi_tanuma`、`lpip`、`teffish`、`necomi`、`chigusa_minori`、`sakiyamama`、`oouso`、`ayuma_sayu`、`houkisei`、`weee_(raemz)` 等 28 位五官比例端正、无结构崩坏且混抽亲和度高的现代画师（附 56 张 250px 预览，合计 251 位 / 502 张预览）。详细审核见 [画师增补记录](artist-expansion-2026-11-25.md)。

## 构建与签名核对

- APK：`versionCode=54`、`versionName=1.1.0`；正式附件 `PocketNAI.apk`，14,874,934 字节。
- 实际编译源码与标签提交：`4afe9dfc84e30913ddc906a5c67f36c4f92a7821`（本发布核对文档提交不改变生产 APK 内容）。
- R8 Release 包在持久 Instabox Android 14 沙箱编译、测试并用既有同源公钥原位签名完成（`BUILD SUCCESSFUL in 4m 15s`、测试预算 `49/50`、`31/31` JVM 单测通过、`GenerationSeedAndPayloadTest` 与 `ArtistLabQueueStateTest` 共 10 项设备集成测试通过、`lintVitalRelease` 通过、APK 内 251 位画师与 502 张预览 SHA-256 逐项核验通过），拉取后沙箱已主动挂起。
- 公开 APK SHA-256：`a3599436f100dd3fb753f813ea6889a3a7a4c60278325885f03e9d8ab1b0c41d`。
- 签名证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`（与 `build-47`、`build-48`、`build-52`、`build-53` 完全一致，支持直接覆盖安装保留本地数据）。
