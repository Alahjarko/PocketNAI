# 0.1.27 发布记录

首发及各轮覆盖记录保留如下；当前同版本覆盖包的源码、摘要及校验以文末最后一轮覆盖记录为准。

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
- 该轮APK大小13,099,883字节，SHA-256：`fbf9c9fc063d6b2ab2b2e0a4844192a3da578426a4d6c76443ef3451111118e4`。
- [同版本替换运行](https://github.com/Alahjarko/PocketNAI/actions/runs/41405768306)仅发布指定摘要的已验证签名包，并在覆盖前确认原附件摘要未改变。公开下载链接已重新下载，与本地签名包及GitHub附件digest一致。

没有设备安装、实际120Hz帧时间或真实生成测试。本次版本号不递增，应用内不会提示更高版本，请从原Release链接手动下载覆盖安装。临时传输分支和签名草稿在完成验证后清理；旧APK与发布元数据备份保留在本次云机的`/workspace/.cloud-setup/release-27/previous/`及`previous-release.json`。

## 队列与费用粗估覆盖更新（2026-11-02）

用户随后授权发版，将[首页生成队列](generation-queue-2026-11-02.md)和[OpenAI费用粗估](openai-pricing-2026-11-02.md)一并发布。沿用同版本覆盖要求，仍为0.1.27/code27，替换`build-27`中的同名APK。标签与Release目标指向实际编译源码`b384e708c2429e33508b47187013acf5d10d0d9f`，含队列提交`8716242`和费用提交`b384e70`。

- OpenAI最多三路并发，提交后可继续编辑和生成；NovelAI先登记排队占位，串行执行，上一个任务收尾后至少间隔1秒。失败停止未发送的NAI后续任务，进程重启不恢复发送，没有自动重试。
- OpenAI生成前按官方2.5计算器给出输出粗估，文本和参考图采用明确标注的本机预算；自动质量显示区间。参考图$0.05是每张成图的本机预留，非官方单价或误差保证。
- 修复响应usage未更新入库的问题，按整次请求用量折算并冻结价格快照；不重复乘张数，缺失用量不当成零费用，部分结果仍保留整次用量。
- 29项JVM测试通过，失败/跳过均0；Debug APK、设备测试Kotlin编译和普通/大字号/深色Layoutlib渲染通过。本地Release完成R8、资源优化和关键Lint检查，包为非调试配置。
- [仅签名运行](https://github.com/Alahjarko/PocketNAI/actions/runs/41454024545)使用项目既有密钥，不重新编译。独立本地验证版本、ZIP对齐及签名前后全部非签名ZIP内容一致；包内版本控制记录也指向上述源码。
- 原签名证书仍为`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与替换前公开APK相同。
- 当前APK大小 **13,116,267字节**，SHA-256：`4ee9181689f36c9dbf67b8fe72924b58aa685f659baa4132d6dd226d6baed89b`。
- [覆盖发布运行](https://github.com/Alahjarko/PocketNAI/actions/runs/41454383549)校验新旧摘要后替换附件；公开下载已重新获取，与本地签名包及GitHub附件digest一致。临时签名草稿和传输分支均已清理。

云端仍无在线Android设备，21项设备测试未执行；没有真实生图或费用误差实测。版本号未递增，需要从[公开APK链接](https://github.com/Alahjarko/PocketNAI/releases/download/build-27/PocketNAI.apk)手动下载覆盖安装。替换前APK、完整元数据及本次验证文件保存在本次云机`/workspace/.cloud-setup/release-27-pricing/`。
