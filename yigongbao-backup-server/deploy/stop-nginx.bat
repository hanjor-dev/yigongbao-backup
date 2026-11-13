@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "PACKAGE_HOME=%~dp0"
set "NGINX_HOME=%PACKAGE_HOME%nginx"
set "NGINX_EXE=%NGINX_HOME%\nginx.exe"
set "PID_FILE=%NGINX_HOME%\logs\nginx.pid"
set "RESULT=0"

if not exist "%NGINX_EXE%" (
    echo [ERROR] nginx.exe not found: %NGINX_EXE%
    set "RESULT=1"
    goto :finish
)
if not exist "%PID_FILE%" (
    echo [OK] Nginx is not running.
    goto :finish
)

set /p NGINX_PID=<"%PID_FILE%"
tasklist /FI "PID eq !NGINX_PID!" /NH 2>nul | findstr /R /C:"[ ]!NGINX_PID![ ]" >nul
if errorlevel 1 (
    del /Q "%PID_FILE%" >nul 2>&1
    echo [OK] Removed stale Nginx PID file.
    goto :finish
)

pushd "%NGINX_HOME%"
"%NGINX_EXE%" -s quit -p "%NGINX_HOME%" -c "conf/nginx.conf"
if errorlevel 1 (
    popd
    echo [ERROR] Failed to send graceful stop signal to Nginx PID=!NGINX_PID!.
    set "RESULT=1"
    goto :finish
)
popd

for /L %%I in (1,1,20) do (
    tasklist /FI "PID eq !NGINX_PID!" /NH 2>nul | findstr /R /C:"[ ]!NGINX_PID![ ]" >nul
    if errorlevel 1 (
        if exist "%PID_FILE%" del /Q "%PID_FILE%" >nul 2>&1
        echo [OK] Nginx stopped.
        goto :finish
    )
    ping 127.0.0.1 -n 2 >nul
)

echo [ERROR] Nginx did not stop within 20 seconds. PID=!NGINX_PID!
set "RESULT=1"

:finish
if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%
