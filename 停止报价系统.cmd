@echo off
rem Stop local quotation services using the registered process identities.
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-Local.ps1"
if errorlevel 1 pause
