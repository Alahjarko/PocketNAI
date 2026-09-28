# 检查更新与发布

本文件是 [根开发约定](../../AGENTS.md) 的专题补充。修改相关功能前必须阅读；跨模块改动需同时阅读索引指向的其他专题。历史实测记录保留其日期，不构成自动发起真实生成、登录或付费调用的授权。

## 检查更新与发布（GitHub Release）

- 发布渠道有两条，**构建号同源**（都取"已有 Release 里最大的 `build-N` 加 1"），因此不会撞号：
  1. **日常用本地发布**：`scripts/publish-release.ps1`（或双击根目录的"发布新版本.bat"）——
     跑单测 → 构建 APK（带下一个构建号）→ 建 Release 并上传，一条龙，不用等云端排队；
  2. 云端 `.github/workflows/build-release.yml` 保留为备份，在 Actions 页面**手动触发**
     （workflow_dispatch）。之前的"push 即构建"已取消：与本地发布并行会撞号，且云端排队慢。
  两条路径发布后都只保留最近 3 个 Release。固定分享链接（永远指向最新构建，适合直接发给用户）：
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
    不比较版本名字符串；解析与判定都是纯函数（`UpdateEvaluator`），有单测钉住；
  - 仓库地址在 `BuildConfig.UPDATE_REPO`（`app/build.gradle.kts`），换仓库只改这一处；
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
  但**生成相关的任何自动化仍然一律禁止**。
