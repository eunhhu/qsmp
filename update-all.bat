@echo off
call "%~dp0update.bat"
if errorlevel 1 exit /b %ERRORLEVEL%
call "%~dp0plugins-resolve.bat"
if errorlevel 1 exit /b %ERRORLEVEL%
call "%~dp0plugins-update.bat"
exit /b %ERRORLEVEL%
