# 0.1.27 发布记录

- 正式版本：[0.1.27 / build-27](https://github.com/Alahjarko/PocketNAI/releases/tag/build-27)，2026-11-02 发布，非草稿、非预发布。
- [APK 下载](https://github.com/Alahjarko/PocketNAI/releases/download/build-27/PocketNAI.apk)，附件名称保持 `PocketNAI.apk`。
- 标签与构建源码：`44ac631dbf3b9335d0da11656362fb0d2686a043`，包含已同步 master 的功能提交 `d6c4fbf`。
- [正式发布构建](https://github.com/Alahjarko/PocketNAI/actions/runs/41394468442)全部成功。

本次仅包含三项：筛选下保留匹配任务的生成占位与等待动画；画廊同时勾选多个模型；默认关闭的可选 SFW 模式。开启 SFW 后，按已记录的正向与角色提示词强模糊相关图片，进入详情仍模糊，再点击图片才临时揭开；新生成在发送前检查，命中则本地拒绝。关闭后保留原有生成能力和参数，不改写原图、导出或分享文件。关键词判断存在误判与漏判，无元数据图片不能据此识别。实现边界见[功能记录](sfw-gallery-2026-11-02.md)。

## 校验

- APK 内 `versionCode=27`、`versionName=0.1.27` 与标签一致；分享任务的包内配置检查通过。
- APK 大小 13,051,052 字节，SHA-256：`62aeae41957620ed9d7379038455554c31aead74d6559216ad0d3380a06caf9d`。本地保存的云端产物、正式发布日志与 GitHub 附件 digest 一致。
- 云端验证签名并与上一正式版比较成功，沿用证书 `d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`。未更换密钥或清除历史数据库。
- 正式构建再次运行 29 项 JVM 回归，零失败、零跳过；[发布前开发验证](https://github.com/Alahjarko/PocketNAI/actions/runs/41393276386)还完成 Debug/Release/AndroidTest APK 编译、109 张 Layoutlib 原生渲染，以及包内版本与签名检查。
- 新预览人工检查多模型勾选、大字体和实际生产高斯变换；模糊样本只使用中性几何图。

21 项设备测试仅编译，未执行。全程没有安卓模拟器或真实 AI 请求。物理设备的触控、图片缓存切换及返回过渡尚未实测，不能把原生渲染或包内配置核对当作真机验收。
