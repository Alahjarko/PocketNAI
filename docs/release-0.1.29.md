# 0.1.29 发布记录

- [正式发布build-29](https://github.com/Alahjarko/PocketNAI/releases/tag/build-29) · [下载APK](https://github.com/Alahjarko/PocketNAI/releases/download/build-29/PocketNAI.apk)，非草稿、非预发布。
- 构建和标签源码：`f98615e8f619907ddb7aa639c7b716ba383c101b`。
- [正式流程](https://github.com/Alahjarko/PocketNAI/actions/runs/41479582682)与[开发验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41476443897)均全部成功。

修正0.1.28颜色按钮点击后的额外开销：移除应用根部逐帧创建整套主题色和广播权重底纹；按钮采用绘制阶段的本地弹簧、按压反馈与固定图标占位，文字不再随选中状态挪位。系统栏仅在相关模式变化时更新。未改动生成、数据、费用和内容规则。具体依据与限制见[颜色响应核对](theme-response-2026-11-03.md)。

同一段主机动画时钟回放执行3次快速切换，每段73帧，示例页面重组从33次降为4次，外层页面/卡片测量均为1次。另完成121张静态原生渲染。该计数不等于物理手机FPS或实际触控延迟，不能据此宣称达到RikkaHub的真机流畅度。

正式包为`versionCode=29`、`versionName=0.1.29`，包内分享任务配置核对通过。文件13,067,436字节，SHA-256为`5f3de7dbe758f03457a8b17353888b9e31af46ddd459215c8e2cd854c68505c5`，本地云端产物与公开附件digest一致。云端验签兼容性通过，本地提取APK v2签名证书亦与原证书`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`一致。

29项JVM回归零失败、零跳过；21项设备测试仅编译。无安卓模拟器、无真实AI请求；真机帧率和触控延迟未实测。旧版代码副本仅用于preview测试源集，不进入安装包。
