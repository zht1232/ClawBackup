@echo off
rem ============================================================
rem  ClawBackup one-click build. Double-click to run.
rem  Calls build.ps1; no PowerShell execution policy setup needed.
rem ============================================================
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
echo.
echo Press any key to close...
pause >nul
