@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-java.ps1"
exit /b %ERRORLEVEL%
