@echo off
setlocal EnableExtensions DisableDelayedExpansion
title YiGongBao Backup Server - Stop

set "JAR_NAME=yigongbao-backup-server.jar"
set "APP_HOME=%~dp0"
set "JAR_PATH=%APP_HOME%%JAR_NAME%"
set "PID_FILE=%APP_HOME%run\backup-server.pid"
set "MARKER_FILE=%APP_HOME%run\backup-server.marker"
set "ARGS_FILE=%APP_HOME%run\backup-server.args"

cd /d "%APP_HOME%"

if not exist "%PID_FILE%" (
    echo [INFO] No PID file was found. The backup server is not running.
    if /i not "%~1"=="--no-pause" pause
    exit /b 0
)

set "BACKUP_PID="
set /p BACKUP_PID=<"%PID_FILE%"
if not defined BACKUP_PID (
    echo [WARN] The PID file is empty and will be removed.
    del /q "%PID_FILE%" >nul 2>&1
    if /i not "%~1"=="--no-pause" pause
    exit /b 0
)

set "BACKUP_CHECK_PID=%BACKUP_PID%"
if not exist "%MARKER_FILE%" (
    echo [WARN] The process marker is missing. No process was stopped.
    del /q "%PID_FILE%" >nul 2>&1
    if /i not "%~1"=="--no-pause" pause
    exit /b 2
)
set "BACKUP_MARKER="
set /p BACKUP_MARKER=<"%MARKER_FILE%"
set "BACKUP_CHECK_MARKER=%BACKUP_MARKER%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$p=Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:BACKUP_CHECK_PID) -ErrorAction SilentlyContinue; if($p -and $p.CommandLine -and $p.CommandLine.IndexOf($env:BACKUP_CHECK_MARKER,[StringComparison]::OrdinalIgnoreCase)-ge 0){exit 0}; exit 1" >nul 2>&1
if errorlevel 1 (
    echo [WARN] PID=%BACKUP_PID% does not belong to this backup server.
    echo [INFO] The stale PID file was removed. No process was stopped.
    del /q "%PID_FILE%" >nul 2>&1
    del /q "%MARKER_FILE%" >nul 2>&1
    del /q "%ARGS_FILE%" >nul 2>&1
    if /i not "%~1"=="--no-pause" pause
    exit /b 2
)

echo [STOP] Stopping backup server. PID=%BACKUP_PID%
taskkill.exe /PID %BACKUP_PID% /T /F >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Cannot stop the process. Run this BAT as Administrator.
    if /i not "%~1"=="--no-pause" pause
    exit /b 3
)

del /q "%PID_FILE%" >nul 2>&1
del /q "%MARKER_FILE%" >nul 2>&1
del /q "%ARGS_FILE%" >nul 2>&1
echo [OK] Backup server stopped.
if /i not "%~1"=="--no-pause" pause
exit /b 0
