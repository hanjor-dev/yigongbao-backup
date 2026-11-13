@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title 医工宝备份系统 - 数据库初始化

rem ============================================================================
rem 客户配置区
rem 1. MySQL 已加入 PATH 时，MYSQL_HOME_CUSTOM 保持为空。
rem 2. 未加入 PATH 时，填写 MySQL 安装根目录，不要填写 bin 或 mysql.exe。
rem    示例：C:\Program Files\MySQL\MySQL Server 8.2
rem 3. MYSQL_ADMIN_USER 必须具备建库、建表、建索引和写入初始数据的权限。
rem ============================================================================
set "MYSQL_HOME_CUSTOM="
set "MYSQL_ADMIN_USER=root"
rem ============================================================================

set "APP_HOME=%~dp0"
set "ENV_PATH=%APP_HOME%.env"
set "RUN_DIR=%APP_HOME%run"
set "EXPECTED_DATABASE=yigongbao_backup"

cd /d "%APP_HOME%"
if not exist "%RUN_DIR%" mkdir "%RUN_DIR%" >nul 2>&1

if not exist "%ENV_PATH%" (
    echo [ERROR] 未找到配置文件：%ENV_PATH%
    echo 请先将 .env.example 复制为 .env，并配置 BACKUP_DB_URL。
    call :PAUSE_IF_NEEDED
    exit /b 10
)

set "DB_BASE_URL="
for /f "usebackq tokens=1 delims=?" %%L in ("%ENV_PATH%") do for /f "tokens=1,* delims==" %%A in ("%%L") do if /i "%%A"=="BACKUP_DB_URL" set "DB_BASE_URL=%%B"

if not defined DB_BASE_URL (
    echo [ERROR] 无法从 .env 的 BACKUP_DB_URL 解析 MySQL 地址。
    echo 正确示例：jdbc:mysql://127.0.0.1:3306/yigongbao_backup?useUnicode=true
    call :PAUSE_IF_NEEDED
    exit /b 11
)

if /i not "%DB_BASE_URL:~0,13%"=="jdbc:mysql://" (
    echo [ERROR] BACKUP_DB_URL 必须以 jdbc:mysql:// 开头。
    call :PAUSE_IF_NEEDED
    exit /b 11
)

set "DB_ENDPOINT=%DB_BASE_URL:~13%"
set "DB_HOST_PORT="
set "DB_HOST="
set "DB_NAME="
for /f "tokens=1,2 delims=/" %%A in ("%DB_ENDPOINT%") do set "DB_HOST_PORT=%%A"& set "DB_NAME=%%B"
set "DB_PORT=3306"
for /f "tokens=1,2 delims=:" %%A in ("%DB_HOST_PORT%") do set "DB_HOST=%%A"& if not "%%B"=="" set "DB_PORT=%%B"

if not defined DB_HOST (
    echo [ERROR] BACKUP_DB_URL 中缺少 MySQL 主机地址。
    call :PAUSE_IF_NEEDED
    exit /b 11
)

if not "%DB_NAME%"=="%EXPECTED_DATABASE%" (
    echo [ERROR] .env 中的数据库名为 "%DB_NAME%"，初始化 SQL 使用 "%EXPECTED_DATABASE%"。
    echo 请先统一 BACKUP_DB_URL，避免服务连接到错误的数据库。
    call :PAUSE_IF_NEEDED
    exit /b 12
)

set "INIT_DIR=%APP_HOME%sql\init"
if exist "%INIT_DIR%\yigongbao-backup-full-init.sql" goto SQL_DIR_FOUND
if exist "%INIT_DIR%\00-create-database.sql" goto SQL_DIR_FOUND
set "INIT_DIR=%APP_HOME%..\sql\init"
if exist "%INIT_DIR%\yigongbao-backup-full-init.sql" goto SQL_DIR_FOUND
if exist "%INIT_DIR%\00-create-database.sql" goto SQL_DIR_FOUND

echo [ERROR] 未找到数据库初始化目录：sql\init。
echo 请将 sql\init 目录与本脚本一起交付。
call :PAUSE_IF_NEEDED
exit /b 13

:SQL_DIR_FOUND
set "INIT_SQL=%INIT_DIR%\yigongbao-backup-full-init.sql"
set "TEMP_COMBINED_SQL="
set "INIT_SOURCE=全量初始化脚本"
set "USING_FULL_INIT=1"
if exist "%INIT_SQL%" goto SQL_READY

set "INIT_SOURCE=分段脚本兜底"
set "USING_FULL_INIT=0"
call :PREPARE_FALLBACK_SQL
if errorlevel 1 (
    call :PAUSE_IF_NEEDED
    exit /b 13
)

:SQL_READY
set "MYSQL_EXE="
if defined MYSQL_EXE_OVERRIDE if exist "%MYSQL_EXE_OVERRIDE%" set "MYSQL_EXE=%MYSQL_EXE_OVERRIDE%"
if defined MYSQL_EXE goto MYSQL_FOUND

if defined MYSQL_HOME_CUSTOM if exist "%MYSQL_HOME_CUSTOM%\bin\mysql.exe" set "MYSQL_EXE=%MYSQL_HOME_CUSTOM%\bin\mysql.exe"
if defined MYSQL_EXE goto MYSQL_FOUND

for %%I in (mysql.exe) do if not "%%~$PATH:I"=="" set "MYSQL_EXE=%%~$PATH:I"
if defined MYSQL_EXE goto MYSQL_FOUND

if defined ProgramFiles for /d %%D in ("%ProgramFiles%\MySQL\MySQL Server *") do if exist "%%~fD\bin\mysql.exe" (
    set "MYSQL_EXE=%%~fD\bin\mysql.exe"
    goto MYSQL_FOUND
)
if defined ProgramFiles(x86) for /d %%D in ("%ProgramFiles(x86)%\MySQL\MySQL Server *") do if exist "%%~fD\bin\mysql.exe" (
    set "MYSQL_EXE=%%~fD\bin\mysql.exe"
    goto MYSQL_FOUND
)

echo [ERROR] 未找到 mysql.exe。
echo 请将 MySQL 加入 PATH，或修改本脚本顶部的 MYSQL_HOME_CUSTOM。
call :CLEAN_COMBINED_SQL
call :PAUSE_IF_NEEDED
exit /b 14

:MYSQL_FOUND
call "%MYSQL_EXE%" --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] MySQL 客户端无法运行：%MYSQL_EXE%
    call :CLEAN_COMBINED_SQL
    call :PAUSE_IF_NEEDED
    exit /b 15
)

echo ============================================================
echo 医工宝备份系统数据库初始化
echo ============================================================
echo MySQL 客户端：%MYSQL_EXE%
echo 服务器：%DB_HOST%:%DB_PORT%
echo 数据库：%DB_NAME%
echo 管理账号：%MYSQL_ADMIN_USER%
echo 初始化来源：%INIT_SOURCE%
echo.
echo 即将创建数据库、表、索引、默认管理员和初始业务配置。
echo 脚本可重复执行，不会清空或覆盖已有业务数据。
echo 接下来请输入 MySQL 管理账号密码，输入时不会显示字符。
echo.

call "%MYSQL_EXE%" --default-character-set=utf8mb4 --protocol=tcp "--host=%DB_HOST%" "--port=%DB_PORT%" "--user=%MYSQL_ADMIN_USER%" --password < "%INIT_SQL%"
set "MYSQL_EXIT=%ERRORLEVEL%"
if not "%MYSQL_EXIT%"=="0" if "%USING_FULL_INIT%"=="1" goto FULL_INIT_FALLBACK
if not "%MYSQL_EXIT%"=="0" goto INIT_FAILED
goto INIT_SUCCESS

:FULL_INIT_FALLBACK
echo.
echo [WARN] 全量初始化脚本执行失败，MySQL 返回码：%MYSQL_EXIT%
echo [WARN] 正在启用 00 至 04 分段脚本兜底，请再次输入 MySQL 管理账号密码。
call :PREPARE_FALLBACK_SQL
if errorlevel 1 (
    call :CLEAN_COMBINED_SQL
    call :PAUSE_IF_NEEDED
    exit /b 13
)
set "INIT_SOURCE=分段脚本报错兜底"
call "%MYSQL_EXE%" --default-character-set=utf8mb4 --protocol=tcp "--host=%DB_HOST%" "--port=%DB_PORT%" "--user=%MYSQL_ADMIN_USER%" --password < "%INIT_SQL%"
set "MYSQL_EXIT=%ERRORLEVEL%"
if not "%MYSQL_EXIT%"=="0" goto INIT_FAILED
goto INIT_SUCCESS

:INIT_FAILED
call :CLEAN_COMBINED_SQL
echo.
echo [ERROR] 数据库初始化失败，MySQL 返回码：%MYSQL_EXIT%
echo 请检查管理员密码、MySQL 服务状态、端口及账号权限后重试。
call :PAUSE_IF_NEEDED
exit /b %MYSQL_EXIT%

:INIT_SUCCESS
call :CLEAN_COMBINED_SQL
echo.
echo [OK] 数据库初始化成功。
echo 已完成：数据库、业务表、索引、默认管理员和初始配置。
echo 下一步可运行 start-backup.bat 启动后端服务。
call :PAUSE_IF_NEEDED
exit /b 0

:PREPARE_FALLBACK_SQL
for %%F in (00-create-database.sql 01-schema.sql 02-indexes.sql 03-base-version.sql 04-functional-config-defaults.sql) do if not exist "%INIT_DIR%\%%F" (
    echo [ERROR] 缺少数据库初始化兜底文件：%%F
    exit /b 13
)
set "TEMP_COMBINED_SQL=%RUN_DIR%\database-init-combined.sql"
set "INIT_SQL=%TEMP_COMBINED_SQL%"
if exist "%INIT_SQL%" del /q "%INIT_SQL%" >nul 2>&1
type "%INIT_DIR%\00-create-database.sql" > "%INIT_SQL%"
echo.>> "%INIT_SQL%"
type "%INIT_DIR%\01-schema.sql" >> "%INIT_SQL%"
echo.>> "%INIT_SQL%"
type "%INIT_DIR%\02-indexes.sql" >> "%INIT_SQL%"
echo.>> "%INIT_SQL%"
type "%INIT_DIR%\03-base-version.sql" >> "%INIT_SQL%"
echo.>> "%INIT_SQL%"
type "%INIT_DIR%\04-functional-config-defaults.sql" >> "%INIT_SQL%"
if not exist "%INIT_SQL%" (
    echo [ERROR] 无法生成临时初始化 SQL，请检查目录写入权限：%RUN_DIR%
    exit /b 13
)
exit /b 0

:CLEAN_COMBINED_SQL
if defined TEMP_COMBINED_SQL if exist "%TEMP_COMBINED_SQL%" del /q "%TEMP_COMBINED_SQL%" >nul 2>&1
exit /b 0

:PAUSE_IF_NEEDED
if not defined BACKUP_INIT_NO_PAUSE pause
exit /b 0
