@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "FRONTEND_URL=http://127.0.0.1"
set "BACKEND_HEALTH_URL=http://127.0.0.1:18080/actuator/health"
set "RESULT=0"

powershell.exe -NoLogo -NoProfile -Command "try { $r=Invoke-WebRequest -UseBasicParsing -Uri '%FRONTEND_URL%/' -TimeoutSec 5; if ($r.StatusCode -eq 200 -and $r.Content -match '医工宝本地备份管理') { exit 0 } } catch {}; exit 1"
if errorlevel 1 (
    echo [ERROR] Frontend is unavailable: %FRONTEND_URL%/
    set "RESULT=1"
) else (
    echo [OK] Frontend is available: %FRONTEND_URL%/
)

powershell.exe -NoLogo -NoProfile -Command "try { $r=Invoke-RestMethod -Uri '%BACKEND_HEALTH_URL%' -TimeoutSec 5; if ($r.status -eq 'UP') { exit 0 } } catch {}; exit 1"
if errorlevel 1 (
    echo [ERROR] Backend health check failed: %BACKEND_HEALTH_URL%
    set "RESULT=1"
) else (
    echo [OK] Backend health is UP: %BACKEND_HEALTH_URL%
)

if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%
