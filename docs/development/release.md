# 检查更新与发布

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

## 检查更新与发布（GitHub Release）

- 2026-11-10用户指定：后续编译、测试和APK打包默认使用已有Instabox工作区，GitHub完整构建仅保留手动备用。`verify-android.yml`已取消开发分支自动触发，避免一次开发反复消耗CI资源。原公开签名密钥未存于沙箱时，沿用下述`sign-local-release.yml`仅签名路径，GitHub不再重跑编译；正式Release仍须核对源码、版本、原证书及公开附件摘要。已开始的0.1.35完整构建按用户要求正常完成，见[发布记录](../release-0.1.35.md)。

- 本地云机没有原签名密钥时，不能使用自动创建的debug keystore。可本地注入目标版本构建优化Release无签名包，再通过`sign-local-release.yml`仅签名，不重新编译或导出密钥。输入通过独立临时Git分支传递，并核对本地SHA-256；签名后先与目标旧版证书比较，结果存于匹配的临时草稿Release。下载核对包内版本和所有编译内容一致后，第二次以`publish_sha256`指定已验证包的摘要，才允许覆盖公开附件；同时核对`previous_sha256`，避免覆盖别人刚更新的包。覆盖既有版本/附件/标签须有用户明确授权；完成后清理自己创建的临时分支和草稿。不要调用默认加号发布脚本来执行同版本覆盖。

- 新版本的本地编译／云端签名：设置 `PNAI_UNSIGNED_RELEASE=1`（只影响 release，不改 debug 签名），同时注入新构建号，再运行单测与 `:app:assembleRelease`。将 `app-release-unsigned.apk` 以 `PocketNAI-unsigned.apk` 上传到 `local-sign-<新构建号>-<源码SHA前12位>` 临时草稿 Release；调用 `sign-local-release.yml`，启用 `unsigned_from_release`，明确设置 `reference_version_code` 为上一公开版本，传入两包摘要与源码 SHA，保持 `publish_sha256` 为空。此模式只签名回传草稿，禁止跨版本覆盖。下载核验签名、版本与全部非签名 ZIP 内容后，再用匹配源码 SHA 创建全新的 `build-N` Release，附件命名 `PocketNAI.apk`；验证公开下载后，仅清理本次临时草稿／标签。无需向 Git 提交 APK，也不把签名密钥导出到云机。

- 2026-11-02：正式发布 workflow 显式安装 `platforms;android-35` 与 `build-tools;34.0.0`，不依赖 runner 的预装版本，不安装/启动模拟器。签名解析兼容 `Signer #1`、SDK 范围与 `V2 Signer` 等输出，仍需实际校验 APK 并比较证书集合。保留已验证 APK 和测试报告为 Actions artifact，再创建正式 Release；标签通过 `--target` 固定到本次构建 SHA，附件仍命名 `PocketNAI.apk`。多行发布说明原样写入文件传给 `--notes-file`。

- 云端 workflow 在发布前下载上一版 `PocketNAI.apk`，用 `apksigner verify --print-certs` 校验两包并比较签名证书SHA-256集合；不一致或无法核验时停止发布，不能以另一把可用密钥替代。用于比较的旧包只读，不覆盖或删除既有Release。

- 从2026-10-09起，两条发布路径均使用 `:app:assembleRelease`，产物为 `app/build/outputs/apk/release/app-release.apk`。启用R8与资源优化，关闭调试开销；`release`继续使用既有debug keystore或CI显式指定的同一密钥，不能另建签名。调试包仍用于日常设备测试，冷启动性能需用同签名优化包另行实测。[Compose官方性能说明](https://developer.android.com/develop/ui/compose/performance)

- Git 提交的作者与提交者使用用户账号 `Alahjarko`，邮箱使用已核实的 GitHub noreply 地址 `143304950+Alahjarko@users.noreply.github.com`；仅设置仓库级 Git 配置，不使用 `PocketNAI Dev` 或其他通用团队身份。
- 发布渠道有两条，**构建号同源**（都取"已有 Release 里最大的 `build-N` 加 1"），因此不会撞号：
  1. **日常用本地发布**：`scripts/publish-release.ps1`（或双击根目录的"发布新版本.bat"）——
     跑单测 → 构建 APK（带下一个构建号）→ 建 Release 并上传，一条龙，不用等云端排队；
  2. 云端 `.github/workflows/build-release.yml` 保留为备份，在 Actions 页面**手动触发**
     （workflow_dispatch）。之前的"push 即构建"已取消：与本地发布并行会撞号，且云端排队慢。
  两条路径均保留既有 Release 与标签，不自动清理旧版本（用户2026-10-10明确要求，先前删除是特殊情况）。删除只能按用户另行指定的版本和范围执行。固定分享链接（永远指向最新构建，适合直接发给用户）：
  `https://github.com/Alahjarko/PocketNAI/releases/latest/download/PocketNAI.apk`。
- **签名密钥绝不能换**：两条路径都必须用本机那把 debug keystore，与所有既有安装签名一致 ——
  换了密钥，新包在用户手机上**无法覆盖安装**，只能卸载重装（丢历史与凭据）。
  本地发布用 AGP 默认路径即可；云端通过 `PNAI_KEYSTORE_PATH` 等环境变量**显式指定**
  keystore 文件（2026-09-18 实测：只把文件放 `~/.android/` 不行，构建用了自动生成的新密钥，
  被应用内的签名校验拦下）。`DEBUG_KEYSTORE_BASE64` 存仓库 Secret，CI 日志会打印其 sha256
  供与本机 `sha256sum ~/.android/debug.keystore` 对照。
- **不能无条件给 `debug.signingConfig` 赋值**（包括赋 `null`）：那会覆盖 AGP 预置的默认
  debug 签名，本地构建产出 `app-debug-unsigned.apk`（构建成功但没签名，2026-09-18 踩中过）。
  正确写法是 `signingConfigs.findByName("pinned")?.let { signingConfig = it }`。
- 版本号：发布时注入 `PNAI_VERSION_CODE` / `PNAI_VERSION_NAME` 环境变量
  （`app/build.gradle.kts` 读取；本地脚本与云端 workflow 都用"已有 Release 里最大编号 + 1"），
  不带变量构建时保持 `1` / `"0.1.0"`。
  **Release tag 里的数字与 APK 的 versionCode 必须同源** —— 应用内更新检测就靠这个比较。
- 应用内检查更新（`domain/update` + `data/update` + `ui/update`）：
  - 判据是**构建号比较**（tag `build-42` → 42 与 `BuildConfig.VERSION_CODE` 比大小），
    不比较版本名字符串；解析与判定都是纯函数（`UpdateEvaluator`）；
  - 仓库地址在 `BuildConfig.UPDATE_REPO`（`app/build.gradle.kts`），换仓库只改这一处；
  - 设置页检查窗口与新版本提示都提供“在 GitHub 查看最新版”（2026-10-02），跳转同一仓库的
    `/releases/latest` 页面；检查失败或已是最新版时入口仍可用，不依赖 API 返回的外部 URL；
  - **只发匿名 GET**（`releases/latest`），不带凭据、不带任何用户数据；这是应用里
    唯一会自动发起的网络请求（启动后延迟数秒静默检查，失败不打扰，设置页可手动检查）；
  - "稍后"记进 `SettingsStore.updateDismissedVersionCode` —— 同一个构建不重复弹，
    出现更新的构建才再提示；
  - 下载后**必须做签名校验**（与当前安装比对，不一致就丢弃）：这是"这个包不该装"
    而不是网络问题，文案要分开；下载与校验失败都不自动重试；
  - 更新包放 `cache/updates/`（不是用户数据，不进 files/、不参与历史清理），
    经 FileProvider（只暴露这一条路径）交给系统安装器；
  - 安装前检查 `canRequestPackageInstalls()`：没有"安装未知应用"授权时先跳系统设置页，
    用户授权回来后再点一次即可（文件已缓存，不会重新下载）。
- 更新检查**不受**"费用未知不得自动发起"约束（它不碰 NovelAI、不花 Anlas），
  生成相关自动化遵循根约定与[对话专题](chat.md)的显式用户授权边界，禁止未授权生成与自动重试。
