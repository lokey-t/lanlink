@echo off
cd /d "%~dp0"
if exist "LanLink.exe" (
  start "" "LanLink.exe"
  exit /b
)
for /f "delims=" %%V in ('powershell -NoProfile -Command "(Get-Content -Raw package.json | ConvertFrom-Json).version"') do set "LANLINK_VERSION=%%V"
if exist "dist\LanLink-%LANLINK_VERSION%-windows-x64\LanLink.exe" (
  start "" "dist\LanLink-%LANLINK_VERSION%-windows-x64\LanLink.exe"
  exit /b
)
echo Build scripts\package-windows.ps1 or extract the native Windows package.
pause
