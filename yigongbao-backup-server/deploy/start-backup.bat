@echo off
setlocal EnableExtensions DisableDelayedExpansion
title YiGongBao Backup Server - Live Log

rem ============================================================================
rem CUSTOMER CONFIGURATION
rem Set JAVA_HOME_CUSTOM to the JDK root directory, not bin or java.exe.
rem Example: C:\Program Files\Java\jdk-21
rem If the delivered JAR is compiled for Java 17, set REQUIRED_JAVA_MAJOR to 17.
rem ============================================================================
set "JAVA_HOME_CUSTOM=C:\Program Files\Java\jdk-21"
set "REQUIRED_JAVA_MAJOR=21"
rem ============================================================================

set "JAR_NAME=yigongbao-backup-server.jar"
set "APP_HOME=%~dp0"
set "JAR_PATH=%APP_HOME%%JAR_NAME%"
set "ENV_PATH=%APP_HOME%.env"
set "RUN_DIR=%APP_HOME%run"
set "LOG_DIR=%APP_HOME%log"
set "PID_FILE=%RUN_DIR%\backup-server.pid"
set "MARKER_FILE=%RUN_DIR%\backup-server.marker"
set "ARGS_FILE=%RUN_DIR%\backup-server.args"
set "CONSOLE_LOG=%LOG_DIR%\console.log"
set "CONSOLE_ERROR_LOG=%LOG_DIR%\console-error.log"
set "JAVA_EXE=%JAVA_HOME_CUSTOM%\bin\java.exe"
set "NIO_TMP_DIR=%SystemRoot%\Temp"

cd /d "%APP_HOME%"

if not exist "%RUN_DIR%" mkdir "%RUN_DIR%" >nul 2>&1
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1

if not exist "%JAR_PATH%" (
    echo [ERROR] JAR not found: %JAR_PATH%
    echo Put this BAT and %JAR_NAME% in the same directory.
    pause
    exit /b 10
)

if not exist "%ENV_PATH%" (
    echo [ERROR] Configuration file not found: %ENV_PATH%
    echo Create and configure .env before starting the server.
    pause
    exit /b 11
)

if not exist "%JAVA_EXE%" (
    echo [ERROR] Java not found: %JAVA_EXE%
    echo Edit JAVA_HOME_CUSTOM at the top of start-backup.bat.
    pause
    exit /b 12
)

set "JAVA_VERSION_FILE=%RUN_DIR%\java-version.tmp"
set "JAVA_VERSION_RESULT=%RUN_DIR%\java-version-result.tmp"
"%JAVA_EXE%" -version >"%JAVA_VERSION_FILE%" 2>&1
if errorlevel 1 (
    echo [ERROR] Cannot execute Java: %JAVA_EXE%
    type "%JAVA_VERSION_FILE%"
    del /q "%JAVA_VERSION_FILE%" >nul 2>&1
    pause
    exit /b 13
)

set "BACKUP_VERSION_FILE=%JAVA_VERSION_FILE%"
set "BACKUP_VERSION_RESULT=%JAVA_VERSION_RESULT%"
set "BACKUP_REQUIRED_MAJOR=%REQUIRED_JAVA_MAJOR%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$lines=Get-Content -LiteralPath $env:BACKUP_VERSION_FILE; if($lines.Count -lt 1){exit 1}; $parts=$lines[0].Split([char]34); if($parts.Length -lt 2){exit 1}; $raw=$parts[1]; $numbers=$raw.Split('.'); if($numbers[0] -eq '1'){$major=[int]$numbers[1]}else{$major=[int]$numbers[0]}; [IO.File]::WriteAllText($env:BACKUP_VERSION_RESULT,($raw+';'+$major),(New-Object Text.UTF8Encoding($false))); if($major -lt [int]$env:BACKUP_REQUIRED_MAJOR){exit 2}" >nul 2>&1
set "VERSION_CHECK_EXIT=%ERRORLEVEL%"
del /q "%JAVA_VERSION_FILE%" >nul 2>&1
if "%VERSION_CHECK_EXIT%"=="1" (
    echo [ERROR] Cannot parse Java version from: %JAVA_EXE%
    pause
    exit /b 14
)
if not exist "%JAVA_VERSION_RESULT%" (
    echo [ERROR] Java version check did not produce a result.
    pause
    exit /b 15
)

set "JAVA_VERSION="
set "JAVA_MAJOR="
for /f "usebackq tokens=1,2 delims=;" %%A in ("%JAVA_VERSION_RESULT%") do set "JAVA_VERSION=%%A"& set "JAVA_MAJOR=%%B"
del /q "%JAVA_VERSION_RESULT%" >nul 2>&1
if "%VERSION_CHECK_EXIT%"=="2" (
    echo [ERROR] Java %JAVA_VERSION% is too old. Java %REQUIRED_JAVA_MAJOR% or newer is required.
    echo Edit JAVA_HOME_CUSTOM at the top of start-backup.bat.
    pause
    exit /b 16
)

set "BACKUP_START_LOG=%CONSOLE_LOG%"

if exist "%PID_FILE%" goto CHECK_EXISTING_PROCESS
goto START_NEW_PROCESS

:CHECK_EXISTING_PROCESS
set "EXISTING_PID="
set /p EXISTING_PID=<"%PID_FILE%"
if not defined EXISTING_PID goto REMOVE_STALE_PID
if not exist "%MARKER_FILE%" goto REMOVE_STALE_PID
set "EXISTING_MARKER="
set /p EXISTING_MARKER=<"%MARKER_FILE%"
if not defined EXISTING_MARKER goto REMOVE_STALE_PID
set "BACKUP_CHECK_PID=%EXISTING_PID%"
set "BACKUP_CHECK_MARKER=%EXISTING_MARKER%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$p=Get-CimInstance Win32_Process -Filter ('ProcessId=' + $env:BACKUP_CHECK_PID) -ErrorAction SilentlyContinue; if($p -and $p.CommandLine -and $p.CommandLine.IndexOf($env:BACKUP_CHECK_MARKER,[StringComparison]::OrdinalIgnoreCase)-ge 0){exit 0}; exit 1" >nul 2>&1
if errorlevel 1 goto REMOVE_STALE_PID
echo [INFO] Backup server is already running. PID=%EXISTING_PID%
goto FOLLOW_LOG

:REMOVE_STALE_PID
echo [INFO] Removing a stale PID file.
del /q "%PID_FILE%" >nul 2>&1
del /q "%MARKER_FILE%" >nul 2>&1
del /q "%ARGS_FILE%" >nul 2>&1

:START_NEW_PROCESS

echo [START] Java: %JAVA_EXE%
echo [START] Version: %JAVA_VERSION%
echo [START] JAR: %JAR_PATH%

set "BACKUP_START_JAVA=%JAVA_EXE%"
set "BACKUP_START_JAR=%JAR_PATH%"
set "BACKUP_START_HOME=%APP_HOME%"
set "BACKUP_START_PID_FILE=%PID_FILE%"
set "BACKUP_START_ERROR_LOG=%CONSOLE_ERROR_LOG%"
set "BACKUP_START_MARKER_FILE=%MARKER_FILE%"
set "BACKUP_START_ARGS_FILE=%ARGS_FILE%"
set "BACKUP_START_NIO_TMP=%NIO_TMP_DIR%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$utf8=New-Object Text.UTF8Encoding($false); $marker=[Guid]::NewGuid().ToString('N'); [IO.File]::WriteAllText($env:BACKUP_START_MARKER_FILE,$marker,$utf8); $portableJar=$env:BACKUP_START_JAR.Replace([char]92,[char]47); $quotedJar=([char]34)+$portableJar+([char]34); $nioTmp=$env:BACKUP_START_NIO_TMP.Replace([char]92,[char]47); [IO.File]::WriteAllLines($env:BACKUP_START_ARGS_FILE,@(('-Djdk.net.unixdomain.tmpdir='+$nioTmp),'-jar',$quotedJar,'--spring.profiles.active=prod'),$utf8); $p=Start-Process -FilePath $env:BACKUP_START_JAVA -ArgumentList @(('-Dyigongbao.launch.marker='+$marker),'@run\backup-server.args') -WorkingDirectory $env:BACKUP_START_HOME -WindowStyle Hidden -RedirectStandardOutput $env:BACKUP_START_LOG -RedirectStandardError $env:BACKUP_START_ERROR_LOG -PassThru; if($null -eq $p){exit 1}; [IO.File]::WriteAllText($env:BACKUP_START_PID_FILE,[string]$p.Id,$utf8)"
if errorlevel 1 (
    echo [ERROR] Cannot create the background process.
    pause
    exit /b 20
)

set "STARTED_PID="
set /p STARTED_PID=<"%PID_FILE%"
set "BACKUP_STARTED_PID=%STARTED_PID%"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "Start-Sleep -Seconds 2; $p=Get-Process -Id ([int]$env:BACKUP_STARTED_PID) -ErrorAction SilentlyContinue; if($p){exit 0}; exit 1" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] The backend process exited during startup. Review the log below.
    del /q "%PID_FILE%" >nul 2>&1
    if exist "%CONSOLE_LOG%" type "%CONSOLE_LOG%"
    if exist "%CONSOLE_ERROR_LOG%" type "%CONSOLE_ERROR_LOG%"
    pause
    exit /b 21
)

echo [OK] Backup server started in the background. PID=%STARTED_PID%

:FOLLOW_LOG
if not exist "%CONSOLE_LOG%" type nul >"%CONSOLE_LOG%"
echo [LOG] Following: %CONSOLE_LOG%
echo [LOG] Ctrl+C or closing this window stops log display only.
echo [LOG] Double-click stop-backup.bat to stop the backend server.
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "Get-Content -Encoding UTF8 -LiteralPath $env:BACKUP_START_LOG -Tail 100 -Wait"
exit /b 0
