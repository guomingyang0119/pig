@echo off
rem Daily start: rebuild changed local sources, then start/reuse healthy services.
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Local.ps1" -AutoUpdate
if errorlevel 1 (
  echo Startup failed. See work\local\logs for details.
  pause
  exit /b 1
)
start "" "http://127.0.0.1:8888"
