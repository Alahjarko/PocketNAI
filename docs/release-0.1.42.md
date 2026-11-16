# PocketNAI 0.1.42 发布核对

2026-11-16 经用户确认后发布 [build-42](https://github.com/Alahjarko/PocketNAI/releases/tag/build-42)。本版落地 Android 原生触觉反馈、连续物理手势、创作直接操纵与加权输入防闪动优化，并保留多状态性能留痕：

- **触觉反馈与收藏图标统一**：新增遵循系统开关、零额外权限的线性马达触觉层 `PocketHaptics`（生成、收藏、滑块分档、画廊拖选、角色拾取与重绘控件），并将抽卡实验室结果收藏按钮统一为爱心图标与「图片收藏」文案。
- **连续手势物理与弹簧动效**：首页生成悬浮层头部支持 1:1 跟手拖拽、临界阻尼弹簧收敛与绘制期背景渐暗遮罩（内部表单滚动互不串扰）；全屏看图器支持围绕双击点弹簧缩放、边界惯性滑行及下拉微缩渐隐关闭。
- **创作直接操纵与防闪修复**：根治 `WeightHighlightedTextField` 多权重提示词逐字输入/退格时的底纹闪动与二次重组；独立角色画布新增 `5×5` + 三分构图线并支持直接按下拖拽任意圆点；局部重绘支持 `1x–5x` 双指捏合缩放平移与实时笔刷/扩张预览圈；标签建议改为单行横向防跳动条；画廊多选支持长按滑过区间连续拖选。
- **沉浸式底栏与性能留痕**：启用透明 Edge-to-Edge 消除底栏白条，成图对勾改为零分配渐进描出；3 项静置窗口保持 0 帧 / 0.00s CPU，150 图冷/热滚动堆分配较 0.1.41 下降 5.8%–13.0%。完整架构与性能报告见 [2026-11-16 人机交互、物理动效与多状态性能留痕](hci-motion-polish-2026-11-16.md)。

## 构建与签名核对

- APK：`versionCode=42`、`versionName=0.1.42`；正式附件 `PocketNAI.apk`，13,431,918 字节。
- 实际编译源码：`f11670a9e938e837fea87ee1bead418f657bd5ed`。正式标签 `build-42` 固定到该实际编译提交；后续工作流与发布核对文档提交不改变生产 APK 内容。
- 全部编译、单测与 R8 资源收缩在持久 InstaBox Android 14 沙箱（`android`）内完成；GitHub [仅签名任务 43808338377](https://github.com/Alahjarko/PocketNAI/actions/runs/43808338377) 与 [校验上架任务 43809141795](https://github.com/Alahjarko/PocketNAI/actions/runs/43809141795) 沿用原项目密钥完成 `apksigner` 签名、证书比对与附件发布，不导出密钥、不运行 Gradle。
- 无签名包 SHA-256：`d07312b818082cd52d466b3bd1c37c1df77be0a85ea669dc0643127f7b1bffff`。
- 公开 APK SHA-256：`57c6cfdc768d82b449a1cb77eb6adfed3bdedc97175e0b882b7ae24b47961366`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与公开 `0.1.41` 完全一致。

下载签回包后经 `apksigner verify --verbose --print-certs` 验证 v2/v3 签名及原版证书一致，589 个非签名 ZIP 条目与 InstaBox 本地编译无签名包逐字节一致，`verify-share-manifest.py` 校验版本与分享入口合同通过。沙箱内设备使用沙箱既有测试签名覆盖更新至 `0.1.42` 并完成 `speed-profile` 编译，保留全部原有登录凭据与历史图片，未触发任何真实生成、超分或登录请求。

已核对公开 Release 为 `latest`、非草稿、非预发布，匿名下载公开 `PocketNAI.apk` 与已验证签名包 SHA-256 完全一致；仅清理本次临时签名草稿与临时输入分支，全部历史正式版本完整保留。沙箱归档位于 `/data/home/workspace/artifacts/release-0.1.42/`。
