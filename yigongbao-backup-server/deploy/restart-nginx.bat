@echo off
setlocal EnableExtensions
chcp 65001 >nul

call "%~dp0stop-nginx.bat" --no-pause
if errorlevel 1 (
    echo [ERROR] Nginx restart aborted because stop failed.
    if /I not "%~1"=="--no-pause" pause
    exit /b 1
)

call "%~dp0start-nginx.bat" --no-pause
set "RESULT=%ERRORLEVEL%"
if "%RESULT%"=="0" echo [OK] Nginx restart completed.
if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%
