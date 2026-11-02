# 0.1.27 发布记录

首发记录保留如下；当前同版本覆盖包的源码、摘要及校验以文末“同版本覆盖更新”为准。

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

## 同版本覆盖更新（2026-11-02）

按用户明确要求，保持`versionCode=27`、`versionName=0.1.27`及`build-27`标签名，替换同一Release的`PocketNAI.apk`。标签与Release目标已从首发源码更新到实际应用源码`d732b3aa688194062eaaf253fd3f063c075e2bf1`，包含SFW小位图解码/缓存优化、NovelAI Wallpaper横竖预设和OpenAI独立宽高滑条及4K预设。

- 本机运行29项JVM回归（失败0、跳过0），完成`:app:assembleRelease`的R8/资源优化及关键Lint检查；包为非调试配置。
- 原签名密钥仅保存在Actions，云机默认debug keystore证书不同，未使用该新密钥签名。应用在本地编译；[仅签名运行](https://github.com/Alahjarko/PocketNAI/actions/runs/41405641939)不调用Gradle、不重新编译。签名前后除签名相关META-INF条目外的全部ZIP内容逐项一致。
- 签名与首发完全一致：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`。签名校验、ZIP对齐、包内版本及分享任务配置均通过。
- 当前APK大小13,099,883字节，SHA-256：`fbf9c9fc063d6b2ab2b2e0a4844192a3da578426a4d6c76443ef3451111118e4`。
- [同版本替换运行](https://github.com/Alahjarko/PocketNAI/actions/runs/41405768306)仅发布指定摘要的已验证签名包，并在覆盖前确认原附件摘要未改变。公开下载链接已重新下载，与本地签名包及GitHub附件digest一致。

没有设备安装、实际120Hz帧时间或真实生成测试。本次版本号不递增，应用内不会提示更高版本，请从原Release链接手动下载覆盖安装。临时传输分支和签名草稿在完成验证后清理；旧APK与发布元数据备份保留在本次云机的`/workspace/.cloud-setup/release-27/previous/`及`previous-release.json`。
