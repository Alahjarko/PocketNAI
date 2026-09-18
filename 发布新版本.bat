@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 即将发布新版本：跑单测 -^> 构建 APK -^> 上传 GitHub Release
echo.
pwsh -NoProfile -ExecutionPolicy Bypass -File "scripts\publish-release.ps1" %*
echo.
pause
