<#
.SYNOPSIS
  批量测试代理列表的可用性（默认目标：NovelAI）。

.DESCRIPTION
  输入文件每行一个代理，格式：host:port:user:pass
  对每个代理依次尝试 HTTP 与 SOCKS5 两种协议：能拿到任意 HTTP 响应
  （哪怕是 401/403）就算"隧道可用" —— 关心的是信道通不通，不是接口鉴权。
  可用的条目按原格式写入 -OutFile（默认：输入文件同目录的 proxies-working.txt）。

.EXAMPLE
  pwsh -NoProfile -File scripts/test-proxies.ps1 -File D:\Downloads\proxies.txt
  pwsh -NoProfile -File scripts/test-proxies.ps1 -Limit 5        # 快速抽查前 5 个
#>
[CmdletBinding()]
param(
    [string]$File = (Join-Path $env:USERPROFILE "Downloads\proxies.txt"),
    [string]$Target = "https://image.novelai.net/",
    [int]$TimeoutSec = 6,
    [int]$Throttle = 8,
    [int]$Limit = 0,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $File)) { throw "找不到代理列表文件：$File" }
if (-not $PSBoundParameters.ContainsKey("OutFile")) {
    $OutFile = Join-Path (Split-Path -Parent $File) "proxies-working.txt"
}

$entries = @(
    Get-Content $File |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -match '^[^:\s]+:\d+:[^:\s]+:[^:\s]+$' }
)
if ($Limit -gt 0) { $entries = @($entries | Select-Object -First $Limit) }
if ($entries.Count -eq 0) { throw "文件里没有可识别的行（期望格式 host:port:user:pass）：$File" }

Write-Host "→ $($entries.Count) 个代理 · 目标 $Target · 并发 $Throttle · 单次超时 $($TimeoutSec)s"
$sw = [Diagnostics.Stopwatch]::StartNew()

$results = $entries | ForEach-Object -Parallel {
    $line = $_
    $target = $using:Target
    $timeoutSec = $using:TimeoutSec
    $parts = $line.Split(':')
    $h = $parts[0]; $p = $parts[1]; $u = $parts[2]; $pw = $parts[3]

    foreach ($scheme in @("http", "socks5h")) {
        $proxyUrl = "$scheme`://${u}:${pw}@${h}:${p}"
        $code = & curl.exe -s --proxy $proxyUrl -o NUL -w "%{http_code}" --max-time $timeoutSec $target 2>$null
        if ($LASTEXITCODE -eq 0 -and $code) {
            [pscustomobject]@{ Ok = $true; Scheme = $scheme; Code = $code; Line = $line }
            return
        }
    }
    [pscustomobject]@{ Ok = $false; Scheme = ""; Code = ""; Line = $line }
} -ThrottleLimit $Throttle

$ok = @($results | Where-Object { $_.Ok })
Write-Host ""
Write-Host "✔ 可用 $($ok.Count) / $($results.Count)"
foreach ($r in ($ok | Sort-Object { $_.Line })) {
    Write-Host ("  [{0}] {1} → HTTP {2}" -f $r.Scheme, $r.Line, $r.Code)
}

if ($ok.Count -gt 0) {
    $ok.Line | Set-Content -Path $OutFile -Encoding utf8
    Write-Host "→ 可用列表（原格式）已写入：$OutFile"
}

Write-Host "耗时 $([math]::Round($sw.Elapsed.TotalSeconds, 1)) 秒"
