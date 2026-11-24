# PocketNAI 1.0.0 发布核对

2026-11-24 经用户确认后发布 [build-47](https://github.com/Alahjarko/PocketNAI/releases/tag/build-47)。本版为 PocketNAI 1.0.0 正式版，全面升级平板与键鼠生产力体验、绘伴多轮对话流、抽卡实验室 1.0 与生成细节交互：

- **1.0 平板键鼠工作台**：宽屏（`>= 720.dp`）顶部新增 `[ 大图预览 | 相册预览 ]` 滑动切换，支持右侧胶片条、滚轮切图 / `Ctrl+滚轮` 缩放、`A`/`D`/`F`/`Space` 快捷键、生成完成悬浮提示、右侧生成栏可拖拽调宽（`320..560.dp`）与一键折叠；画廊新增鼠标悬停快捷操作、右键菜单与鼠标拖拽挂载参考图（完整保留触控长按操作卡片），支持跨应用拖图、提示词 `Ctrl+↑/↓` 调权、`Ctrl+Enter` 快速生成、`Ctrl+F` 聚焦搜索与全局 `Ctrl+/` 快捷键速查。
- **绘伴（Chat）宽屏与多轮创作流**：宽屏（`>= 840.dp`）新增可折叠左侧历史对话栏；支持外接键盘 `Enter` 直接发送 / `Ctrl+Enter` 发送双模式切换与 `Ctrl+N` 新建对话；消息气泡新增鼠标悬停与右键菜单（编辑 / 重试 / 从此开新分支 / 复制正文），左上角历史底栏支持新建对话，并打通对话内拖图与参考图生图（NovelAI / OpenAI）。
- **抽卡实验室 1.0**：新增宽屏双栏布局（左侧嵌入式实验记录与控制，右侧网格与可切换大图检视，支持 `A`/`D`、`F`、`Esc`）；支持锁定 1–3 位基底画师与其余随机混搭、主高辅低阶梯权重、基础提示词自动剥离 `artist:` 标签、结果卡片一键排除画师或设为锁定基底、一键将画师串应用到首页生成面板，以及 2–4 张同屏 AB 对比。
- **生成、详情、局部重绘与设置升级**：设置新增可选独立画师提示词框与宽屏双栏分类导航；V5 模型下明确禁用 Precise / Vibe 参考标签；画廊与详情页支持一键用作参考图与复用参数；详情与全屏视图新增键盘与滚轮缩放/切图；局部重绘新增鼠标/手写笔悬浮笔刷圈、滚轮与 `[` `]` 调大小、`Ctrl+Z` 撤销及右键/手写笔橡皮擦临时擦除。

## 构建与签名核对

- APK：`versionCode=47`、`versionName=1.0.0`；正式附件 `PocketNAI.apk`，13,628,526 字节。
- 应用编译源码：`e1f7a4e7c452bcc3e78d9bf577cc0c2458153ee3`；正式标签 `build-47` 固定到提交 `6903457ef84ffaa60b6eda4eb8c94886f2e3144d`（仅放宽 `scripts/verify-share-manifest.py` 对 `1.x` 语义化正式版本号的校验，未改动任何 `app/` 源码；本发布核对文档提交亦不改变生产 APK 内容）。
- 测试预算与编译验证在持久 Instabox Android 14 沙箱内完成：`:app:checkTestBudget` 通过（`49/50`，JVM 31 + Android 18），`:app:testDebugUnitTest` 全部通过（`31/31`），`:app:compileDebugAndroidTestKotlin` 编译通过；R8 资源收缩与无签名 Release 构建在沙箱内完成并验证 `zipalign -c 4` 通过。
- 通过既定双阶段 `sign-local-release.yml` 仅签名不重编流程完成云端签名与发布：
  - 签名与校验任务：[45326961443](https://github.com/Alahjarko/PocketNAI/actions/runs/45326961443)（比对 `build-46` 签名证书一致、校验分享入口与版本号、校验签名包与本地无签名包非签名 ZIP 条目逐字节一致）。
  - 校验上架任务：[45327111434](https://github.com/Alahjarko/PocketNAI/actions/runs/45327111434)（校验已签名包 SHA-256 后直接发布至公开 Release `build-47`）。
- 无签名包 SHA-256：`3f09d0b8d734e0171f046bfb8ae8933fd6a50622be37c1c5c966532ea6a8a92a`（13,570,347 字节）。
- 公开 APK SHA-256：`46ac64f08fed553d6ae42fc5755c2e8890aeac5d380d451b0603292e9eb485c8`（13,628,526 字节）。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与公开 `0.1.46` 完全一致。

已核对公开 Release `build-47`（`PocketNAI 1.0.0`）为 `Latest`、非草稿、非预发布，附件 `PocketNAI.apk` 摘要与已验证签名包一致；仅清理本次临时草稿，全部历史正式版本完整保留；沙箱实例已主动挂起。
