@echo off
echo This will back up and reset the current map.
set /p CONFIRM=Type RESET to continue: 
if /I not "%CONFIRM%"=="RESET" (
  echo Cancelled.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0world.ps1" reset -ConfirmReset
exit /b %ERRORLEVEL%
