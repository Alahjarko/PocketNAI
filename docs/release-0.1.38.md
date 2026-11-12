# PocketNAI 0.1.38 发布核对

2026-11-12 经用户确认后发布 [build-38](https://github.com/Alahjarko/PocketNAI/releases/tag/build-38)，包含首次欢迎页、常用按钮按压与局部反馈、参考图过渡、成图交接，以及绘伴对话多图详情左右切换修复。功能与设备验收见[动效记录](motion-polish-2026-11-12.md)和[多图浏览记录](chat-image-pager-2026-11-12.md)。

- APK：`versionCode=38`、`versionName=0.1.38`，附件 `PocketNAI.apk`。
- 编译源码：`4fff1f39d837478906d8724b4dde95b55c05909f`。发布前已同步 master，标签固定到实际编译提交；之后的提交只补充验收和发布文档。
- 使用此前在现有 Instabox 构建并验收的 R8 优化包，没有再次编译或启动设备。GitHub [43210801533](https://github.com/Alahjarko/PocketNAI/actions/runs/43210801533) 只使用原密钥签名和核验，结果成功。
- 无签名包 SHA-256：`43485aff090fd3f9d12d0cb5a1f30f6a3167d4edec80c01b9875080db23f977a`。
- 公开 APK：13,362,027 字节，SHA-256：`6302bb5dfaf4bac11f3369f16ebc9ad0272ccd4c01a2ff2ce724e8a4bdc2c8f9`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与上一公开版 0.1.37 一致。

签名流程完成 apksigner 校验；下载签名包后，在主机使用官方 apksig 8.11.1 再次验证完整签名并比较两版证书，核对全部非签名 ZIP 内容与已验收无签名包逐字节相同，版本与分享入口 manifest 合同正确。没有导出公开签名密钥或更换云端设备上的既有测试签名。

沿用相同编译源码的 29 项 JVM、21 项 Android 回归结果，以及优化包实际多图滑动、首尾边界、返回位置、欢迎导航、大字号与操作反馈验收。发布阶段没有修改应用代码，也没有重复发起真实聊天或生图。

已核对正式 Release 为 latest、非草稿、非预发布，标签指向编译源码，附件大小和摘要正确。匿名下载公开 APK 后，与已验证签名包的全部字节一致。发布证据与 APK 保存在工作区 `pocketnai/artifacts/release-0.1.38/`；云端设备维持挂起。
