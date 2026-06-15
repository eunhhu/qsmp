@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0plugins.ps1" check
exit /b %ERRORLEVEL%
