@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0world.ps1" inject
exit /b %ERRORLEVEL%
