@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1" check
exit /b %ERRORLEVEL%
