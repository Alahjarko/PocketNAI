# 0.1.30 发布记录

- [正式发布build-30](https://github.com/Alahjarko/PocketNAI/releases/tag/build-30) · [下载APK](https://github.com/Alahjarko/PocketNAI/releases/download/build-30/PocketNAI.apk)，非草稿、非预发布。
- 构建和标签源码：`175243e7d16e8d27f8b87d3e0f2d72b8c7dc28b4`。正式发布前已同步到master。
- [正式流程](https://github.com/Alahjarko/PocketNAI/actions/runs/41566390906)与[开发验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41565778519)均全部成功。开发验证之后只有核对文档变更，生产代码一致。

颜色模式切换新增从所选按钮中心向外展开的约400ms圆形揭示，适用于浅色、深色和跟随系统。实际明暗相同不播放，快速反向切换保留最后选择。新页面使用独立图层缓存，主题一次提交，按钮保持按压和选中反馈；导航、后台和窗口尺寸变化清理临时覆盖，捕获失败仍直接保存设置。具体依据、八组动画回放和验收边界见[圆形主题揭示](circular-theme-reveal-2026-11-03.md)。

正式包为`versionCode=30`、`versionName=0.1.30`，包内分享任务配置核对通过。文件13,083,820字节，SHA-256为`92d94a1b85484d10cb6cf0db09cec5285ff10b2422f2c99775c2a66e1529d422`，本地云端产物与公开附件digest一致。云端完整验签及兼容性比较通过，本地提取APK v2签名证书亦与原证书`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`一致，可覆盖更新。

29项JVM回归零失败、零跳过；121张静态预览及10段原生APNG，其中新增八组圆形揭示共488帧，普通切换最远角在捕获后约400ms变色。21项设备测试仅编译，无安卓模拟器、无真实AI请求。主机快照适配器使用上一张真实Layoutlib帧，手机GPU快照路径、触控延迟和物理帧率尚未实测。
