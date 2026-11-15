# PocketNAI 0.1.40 发布核对

2026-11-15 经用户确认后发布 [build-40](https://github.com/Alahjarko/PocketNAI/releases/tag/build-40)，完善 MCP 正文图片与 NovelAI 独立角色控制。更新后请在 RikkaHub 重新连接/刷新 MCP 工具列表，读取新增的 `characters`、`use_coords` 参数。实现、原生截图与验证边界见[正文图片](mcp-inline-images-2026-11-15.md)和[独立角色记录](mcp-characters-2026-11-15.md)。

- APK：`versionCode=40`、`versionName=0.1.40`；正式附件 `PocketNAI.apk`，13,415,534 字节。
- 实际编译源码：`2e462d74be4a7e89c7955daaba02b16aac850496`。已验收改动与文档先快进同步到 master；正式标签固定到该编译提交，后续发布文档不改变生产内容。
- 直接使用既有 Instabox 产物，没有重新编译。GitHub [仅签名任务 43660419556](https://github.com/Alahjarko/PocketNAI/actions/runs/43660419556) 成功，沿用原密钥签名与校验，不导出密钥、不运行 Gradle；本轮没有恢复云端设备。
- 无签名包 SHA-256：`0658fce470abff3876deeef3f61a9d03976aebddad30ea08a74b329fd36cd289`。
- 公开 APK SHA-256：`7ea0995e6f4c0e96acfdd299aed5a8f97fc509cda83197c849b52f3d566654cd`。
- 公开证书 SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与 0.1.39 相同。

签名任务完成 apksigner 校验；主机下载后使用官方 apksig 8.11.1 验证完整签名并比较上一公开版证书。新签名包与已验收无签名包的全部非签名 ZIP 内容逐字节一致，版本与分享入口 manifest 合同正确。不更换云端设备的测试签名，也不通过卸载或清数据安装公开包。

沿用该源码的29项JVM、21项Android回归和先行8项重点检查，以及真实 RikkaHub 3.2.0 客户端的离线双角色、逐张确认、正负数组和坐标对应、单次生成、640px正文大图、点击全屏与返回验收。联调仅用假接口；发布阶段没有调用真实 LLM、搜索或生图。

已核对 Release 为 latest、非草稿、非预发布，标签指向实际编译提交，附件名、大小与摘要正确。匿名 API 同样返回 build-40；匿名下载的公开 APK 与已验证签名包完全相同。仅清理本次临时签名草稿，确认没有遗留临时标签；保留历史公开版本和开发分支。证据与 APK 在工作区 `pocketnai/artifacts/release-0.1.40/`。
