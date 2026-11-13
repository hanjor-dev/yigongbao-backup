@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "PACKAGE_HOME=%~dp0"
set "NGINX_HOME=%PACKAGE_HOME%nginx"
set "NGINX_EXE=%NGINX_HOME%\nginx.exe"
set "NGINX_CONFIG=conf/nginx.conf"
set "FRONTEND_URL=http://127.0.0.1"
set "RESULT=0"

if not exist "%NGINX_EXE%" (
    echo [ERROR] nginx.exe not found: %NGINX_EXE%
    set "RESULT=1"
    goto :finish
)
if not exist "%NGINX_HOME%\conf\nginx.conf" (
    echo [ERROR] nginx.conf not found.
    set "RESULT=1"
    goto :finish
)
if not exist "%PACKAGE_HOME%frontend\dist\index.html" (
    echo [ERROR] Frontend build not found: frontend\dist\index.html
    set "RESULT=1"
    goto :finish
)

for %%D in (logs temp temp\client_body_temp temp\proxy_temp) do (
    if not exist "%NGINX_HOME%\%%D" mkdir "%NGINX_HOME%\%%D" >nul 2>&1
)

pushd "%NGINX_HOME%"
"%NGINX_EXE%" -t -p "%NGINX_HOME%" -c "%NGINX_CONFIG%"
if errorlevel 1 (
    popd
    echo [ERROR] Nginx configuration validation failed.
    set "RESULT=1"
    goto :finish
)

if exist "%NGINX_HOME%\logs\nginx.pid" (
    set /p NGINX_PID=<"%NGINX_HOME%\logs\nginx.pid"
    tasklist /FI "PID eq !NGINX_PID!" /NH 2>nul | findstr /R /C:"[ ]!NGINX_PID![ ]" >nul
    if not errorlevel 1 (
        popd
        echo [OK] Nginx is already running. PID=!NGINX_PID!
        goto :health
    )
    del /Q "%NGINX_HOME%\logs\nginx.pid" >nul 2>&1
)

powershell.exe -NoLogo -NoProfile -Command "$arguments=@('-p',('"' + '%NGINX_HOME%' + '"'),'-c','%NGINX_CONFIG%'); $process=Start-Process -FilePath '%NGINX_EXE%' -ArgumentList $arguments -WorkingDirectory '%NGINX_HOME%' -WindowStyle Hidden -PassThru; if ($null -eq $process) { exit 1 }"
if errorlevel 1 (
    popd
    echo [ERROR] Failed to launch Nginx background process.
    set "RESULT=1"
    goto :finish
)
popd

:health
for /L %%I in (1,1,30) do (
    powershell.exe -NoLogo -NoProfile -Command "try { $r=Invoke-WebRequest -UseBasicParsing -Uri '%FRONTEND_URL%/nginx-health' -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } } catch {}; exit 1" >nul 2>&1
    if not errorlevel 1 (
        echo [OK] Nginx started. Frontend: %FRONTEND_URL%/
        goto :finish
    )
    ping 127.0.0.1 -n 2 >nul
)

echo [ERROR] Nginx process did not become healthy. Check nginx\logs\error.log.
set "RESULT=1"

:finish
if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%
