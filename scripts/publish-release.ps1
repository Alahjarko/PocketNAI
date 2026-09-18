<#
.SYNOPSIS
  本地发布新版本：跑单测 → 构建 APK → 发布 GitHub Release。

.DESCRIPTION
  构建号 = GitHub 上最大的 Release 编号 + 1 —— 与云端 workflow 用同一规则，
  两条发布路径不会撞号。
  APK 用本机的 debug keystore 签名：与用户手机上所有既有安装一致，
  可以直接覆盖安装、不丢数据（这是"应用内更新"能工作的前提）。
  发布后只保留最近 3 个 Release，与云端一致。

.EXAMPLE
  pwsh -NoProfile -File scripts\publish-release.ps1
  pwsh -NoProfile -File scripts\publish-release.ps1 -Message "feat: 新功能" -SkipTests
#>
[CmdletBinding()]
param(
    [string]$Message,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Checked {
    param([scriptblock]$Command, [string]$What)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What 失败（退出码 $LASTEXITCODE）" }
}

# ---- 0. 前置检查 ----
$remote = (git remote get-url origin) -replace '\.git$', '' -replace '^.*github\.com[:/]', ''
if (-not $remote) { throw "读不到 origin 远端，请确认这是 PocketNAI 仓库" }
Write-Host "→ 发布目标：$remote"

# ---- 1. 计算下一个构建号 ----
$tags = gh release list --limit 100 --json tagName --jq '.[].tagName'
if ($LASTEXITCODE -ne 0) { throw "读取 Release 列表失败（gh 是否已登录？）" }
$numbers = @($tags | ForEach-Object { if ($_ -match '^build-(\d+)$') { [int]$Matches[1] } })
$next = if ($numbers.Count -gt 0) { ($numbers | Measure-Object -Maximum).Maximum + 1 } else { 1 }
Write-Host "→ 本次构建号：$next（版本名 0.1.$next）"

# ---- 2. 单元测试 ----
if (-not $SkipTests) {
    Write-Host "→ 跑单元测试…"
    Invoke-Checked { & .\gradlew.bat :app:testDebugUnitTest } "单元测试"
}

# ---- 3. 构建 ----
Write-Host "→ 构建 APK…"
# 本地必须用本机默认的 debug keystore（PNAI_KEYSTORE_PATH 是 CI 专用路径参数，
# 万一当前 shell 里残留了它，会签出与既有安装不一致的包）。
$env:PNAI_KEYSTORE_PATH = $null
$env:PNAI_VERSION_CODE = "$next"
$env:PNAI_VERSION_NAME = "0.1.$next"
try {
    Invoke-Checked { & .\gradlew.bat :app:assembleDebug } "构建"
} finally {
    Remove-Item Env:PNAI_VERSION_CODE -ErrorAction SilentlyContinue
    Remove-Item Env:PNAI_VERSION_NAME -ErrorAction SilentlyContinue
}

$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "找不到构建产物：$apk" }

# 附件必须叫 PocketNAI.apk：应用侧检测与"永远指向最新"的分享链接都认这个名字。
$stage = Join-Path $env:TEMP "PocketNAI.apk"
Copy-Item $apk $stage -Force

# ---- 4. 发布 ----
$headline = if ($Message) { $Message } else { (git log -1 --pretty=%s) }
$notes = "构建 #$next · 本地构建 · $headline"
Write-Host "→ 创建 Release build-$next…"
Invoke-Checked { & gh release create "build-$next" $stage --title "0.1.$next" --notes $notes } "创建 Release"

# ---- 5. 只保留最近 3 个构建 ----
Write-Host "→ 清理旧构建（保留最近 3 个）…"
$stale = gh release list --limit 100 --json tagName --jq '.[3:][] | .tagName'
foreach ($tag in $stale) {
    if ($tag) { gh release delete $tag --yes --cleanup-tag | Out-Null }
}

Write-Host ""
Write-Host "✔ 发布完成"
Write-Host "  本次：$remote/releases/tag/build-$next"
Write-Host "  最新直链（发给用户用这个）：$remote/releases/latest/download/PocketNAI.apk"
