# 0.1.26 发布记录

- 正式版本：[0.1.26 / build-26](https://github.com/Alahjarko/PocketNAI/releases/tag/build-26)，2026-11-02 发布，非草稿、非预发布。
- [APK 下载](https://github.com/Alahjarko/PocketNAI/releases/download/build-26/PocketNAI.apk)，附件名称保持 `PocketNAI.apk`。
- 标签与构建源码：`b3076ae229b7e8f471945d5ad51eca09c3afd574`；代码已同步 master。
- [正式发布构建](https://github.com/Alahjarko/PocketNAI/actions/runs/41388138355)全部成功。

本次包括：颜色模式直接切换；令牌、会员、Anlas 与波浪限额整合；原创变形光核；助手逐帧显示、Markdown 末段解析与历史回看；画廊选图附带生成参数及“仅发送图片”；OpenAI Flare/Sunburst 画廊筛选；相册分享转交主任务，避免主界面继承外部临时任务标记。实现与分阶段验证见[体验改造记录](experience-polish-2026-11-02.md)。

## 校验

- 安装包内 `versionCode=26`、`versionName=0.1.26` 与标签一致；主入口为 singleTask/never，分享接收器 affinity 为空且不保留最近任务。
- 云端 APK SHA-256：`096b5dd907e4205ed2247ff5ccb3fb6a88e992c24d63aa4f5ebad4c83eee1f59`，与正式附件的 GitHub digest 一致，附件大小 13,034,668 字节。
- APK 验签并与上一正式版比较成功，沿用证书 `d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`；没有清除或迁移历史数据库。
- 正式构建再次运行 29 项 JVM 回归；[发布前开发验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41387775346)还完成 Debug/Release/AndroidTest APK 编译、原生组件渲染和打包配置检查。

21 项设备测试仅编译，未执行。全程没有安卓模拟器或真实 AI 请求。系统最近任务的 Home/后台/恢复行为、真机手势、输入法与帧率仍需物理设备验收，不能把包内配置检查或 Layoutlib 预览当作真机验证。
