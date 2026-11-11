# PocketNAI 0.1.37 发布核对

2026-11-11发布[build-37](https://github.com/Alahjarko/PocketNAI/releases/tag/build-37)，修复普通NovelAI、OpenAI及绘伴生图在锁屏/后台容易中断的问题。具体生命周期与系统边界见[后台生成记录](background-generation-2026-11-11.md)。

- APK：`versionCode=37`、`versionName=0.1.37`，附件`PocketNAI.apk`。
- 编译源码：`5a39bd641ff337cc6d2e256495b28fbe755a8190`，发布前已同步master；标签固定到该提交。
- 编译和R8在用户已有Instabox工作区完成，不新建安卓环境。GitHub[42944998622](https://github.com/Alahjarko/PocketNAI/actions/runs/42944998622)只使用原密钥签名和核验，已成功，没有重跑Gradle。
- 无签名包SHA-256：`55fc56fb5589bc554e8563a7d15b8971d97e98e746c0c0bb47e63d209c05f0cb`。
- 公开APK大小：13,345,643字节；SHA-256：`10f6c08e135c92df08a18e8d820e228fc878065ebec67b7a7fa354914c7fd49f`。
- 公开证书SHA-256：`d55f01c767abf1f1e4134737582a1ef8265cccb9392a32e863c44de94765cab6`，与0.1.36一致。签名流程完整执行apksigner验证；下载后另行核对证书、版本及全部非签名ZIP内容与云端无签名包逐字节一致。

29项JVM（无失败、无跳过）与21项Android设备回归全绿。生成链路7项及后台专项另行通过；最后的R8构建再次通过29项JVM。设备覆盖普通生成、OpenAI与绘伴共用保护、Home/熄屏/Activity重建及结束、确认前零图片请求、等待时释放及确认后重获唤醒锁、单次请求和任务退出清理。均使用隔离数据和假接口，没有代发真实LLM/搜索/生图。

设备优化包沿用沙箱原测试证书覆盖安装，与公开包的应用内容相同；没有导出公开签名密钥、更换既有设备签名、卸载或清数据。实际截图已确认通知以及优化包首页历史图片保留；通知QA临时授权后已恢复未授权状态。构建与验收结束后保存产物并挂起原Instabox实例，确认无运行中的Gradle或生成前台服务。

已核对正式Release为latest、非草稿、非预发布，附件摘要及标签提交正确。已匿名下载公开APK，摘要和全部字节与已验证签名包一致；临时签名草稿已清理，既有正式Release保持不变。证据与APK在工作区`artifacts/background-generation-2026-11-11`。
