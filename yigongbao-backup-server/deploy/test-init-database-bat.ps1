$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceBat = Join-Path $scriptDirectory 'init-database.bat'
$sourceSqlDirectory = Join-Path (Split-Path -Parent $scriptDirectory) 'sql\init'
$sourceSqlFiles = @(
    'yigongbao-backup-full-init.sql',
    '00-create-database.sql',
    '01-schema.sql',
    '02-indexes.sql',
    '03-base-version.sql',
    '04-functional-config-defaults.sql'
)

Assert-True (Test-Path -LiteralPath $sourceBat) 'init-database.bat 不存在。'
foreach ($sqlFile in $sourceSqlFiles) {
    Assert-True (Test-Path -LiteralPath (Join-Path $sourceSqlDirectory $sqlFile)) "初始化 SQL 不存在：$sqlFile"
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yigongbao-db-init-test-' + [Guid]::NewGuid().ToString('N'))
$packageRoot = Join-Path $testRoot 'package'
$sqlDirectory = Join-Path $packageRoot 'sql\init'
$fakeMysql = Join-Path $testRoot 'mysql.cmd'
$callLog = Join-Path $testRoot 'mysql-call.log'
$stdinLog = Join-Path $testRoot 'mysql-stdin.sql'

try {
    New-Item -ItemType Directory -Path $sqlDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceBat -Destination (Join-Path $packageRoot 'init-database.bat')
    foreach ($sqlFile in $sourceSqlFiles) {
        Copy-Item -LiteralPath (Join-Path $sourceSqlDirectory $sqlFile) -Destination (Join-Path $sqlDirectory $sqlFile)
    }
    Add-Content -LiteralPath (Join-Path $sqlDirectory '01-schema.sql') -Value "`nSELECT 'SPLIT_FALLBACK_ONLY';" -Encoding utf8

    @'
SPRING_PROFILES_ACTIVE=prod
BACKUP_DB_URL=jdbc:mysql://192.0.2.10:3307/yigongbao_backup?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai
BACKUP_DB_USERNAME=backup
BACKUP_DB_PASSWORD=must-not-appear
'@ | Set-Content -LiteralPath (Join-Path $packageRoot '.env') -Encoding utf8

    @'
@echo off
setlocal DisableDelayedExpansion
if "%~1"=="--version" exit /b 0
set "FIRST_LINE="
set /p FIRST_LINE=
>>"%FAKE_MYSQL_LOG%" echo ARGS:%*
>>"%FAKE_MYSQL_LOG%" echo STDIN:%FIRST_LINE%
>"%FAKE_MYSQL_STDIN%" echo %FIRST_LINE%
more >>"%FAKE_MYSQL_STDIN%"
if defined FAKE_MYSQL_FAIL_FIRST_MARKER if not exist "%FAKE_MYSQL_FAIL_FIRST_MARKER%" (
    >"%FAKE_MYSQL_FAIL_FIRST_MARKER%" echo failed
    exit /b 65
)
if "%FAKE_MYSQL_SHOULD_FAIL%"=="1" exit /b 66
exit /b 0
'@ | Set-Content -LiteralPath $fakeMysql -Encoding ascii

    $env:MYSQL_EXE_OVERRIDE = $fakeMysql
    $env:FAKE_MYSQL_LOG = $callLog
    $env:FAKE_MYSQL_STDIN = $stdinLog
    $env:BACKUP_INIT_NO_PAUSE = '1'
    Remove-Item Env:FAKE_MYSQL_SHOULD_FAIL -ErrorAction SilentlyContinue

    $packageBat = Join-Path $packageRoot 'init-database.bat'
    $output = (& $packageBat 2>&1 | Out-String)
    $successExitCode = $LASTEXITCODE

    Assert-True (Test-Path -LiteralPath $callLog) "MySQL 测试替身未被调用。`n$output"
    $calls = Get-Content -Raw -LiteralPath $callLog
    $executedSql = Get-Content -Raw -LiteralPath $stdinLog
    Assert-True ($successExitCode -eq 0) "成功场景退出码应为 0，实际为 $successExitCode。`n$output"
    Assert-True ($calls -match '--host=192\.0\.2\.10') '未从 .env 解析数据库主机。'
    Assert-True ($calls -match '--port=3307') '未从 .env 解析数据库端口。'
    Assert-True ($calls -match '--user=root') '未使用默认数据库管理员 root。'
    Assert-True ($calls -match '--password') '未要求 MySQL 客户端交互输入密码。'
    Assert-True ($calls -match 'STDIN:/\*') '未将全量初始化 SQL 传给 MySQL 标准输入。'
    Assert-True ($calls -notmatch 'must-not-appear') '应用数据库密码泄漏到了 MySQL 命令行。'
    Assert-True ($executedSql.Contains('FINAL VERIFICATION')) '全量脚本存在时，应优先提交 yigongbao-backup-full-init.sql。'
    Assert-True (-not $executedSql.Contains('SPLIT_FALLBACK_ONLY')) '全量脚本存在时，不应执行分段兜底脚本。'
    $sqlMarkers = @(
        'CREATE DATABASE IF NOT EXISTS yigongbao_backup',
        'CREATE TABLE IF NOT EXISTS bk_user',
        'CREATE PROCEDURE add_backup_index_if_missing',
        'CREATE PROCEDURE add_backup_version_if_missing',
        "SELECT 'backup.schedule.enabled'"
    )
    foreach ($marker in $sqlMarkers) {
        Assert-True ($executedSql.Contains($marker)) "提交给 MySQL 的内容缺少初始化阶段：$marker"
    }
    $lastPosition = -1
    foreach ($marker in $sqlMarkers) {
        $position = $executedSql.IndexOf($marker, [StringComparison]::Ordinal)
        Assert-True ($position -gt $lastPosition) "初始化 SQL 顺序错误：$marker"
        $lastPosition = $position
    }
    Assert-True ($output -match '\[OK\]') '成功输出缺少 [OK] 标记。'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'run\database-init-combined.sql'))) '成功后未清理临时组合 SQL。'

    Remove-Item -LiteralPath $callLog, $stdinLog -Force
    Move-Item -LiteralPath (Join-Path $sqlDirectory 'yigongbao-backup-full-init.sql') `
        -Destination (Join-Path $testRoot 'yigongbao-backup-full-init.sql')
    $fallbackOutput = (& $packageBat 2>&1 | Out-String)
    $fallbackExitCode = $LASTEXITCODE
    $fallbackSql = Get-Content -Raw -LiteralPath $stdinLog

    Assert-True ($fallbackExitCode -eq 0) "全量脚本缺失时，分段兜底初始化应成功。`n$fallbackOutput"
    Assert-True ($fallbackSql.Contains('SPLIT_FALLBACK_ONLY')) '全量脚本缺失时，未执行分段兜底脚本。'
    Assert-True (-not $fallbackSql.Contains('FINAL VERIFICATION')) '分段兜底场景不应读取全量脚本。'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'run\database-init-combined.sql'))) '兜底初始化成功后未清理临时组合 SQL。'

    Remove-Item -LiteralPath $callLog, $stdinLog -Force
    Move-Item -LiteralPath (Join-Path $testRoot 'yigongbao-backup-full-init.sql') `
        -Destination (Join-Path $sqlDirectory 'yigongbao-backup-full-init.sql')
    $env:FAKE_MYSQL_FAIL_FIRST_MARKER = Join-Path $testRoot 'mysql-first-failure.marker'
    $retryOutput = (& $packageBat 2>&1 | Out-String)
    $retryExitCode = $LASTEXITCODE
    $retryCalls = Get-Content -Raw -LiteralPath $callLog
    $retrySql = Get-Content -Raw -LiteralPath $stdinLog

    Assert-True ($retryExitCode -eq 0) "全量初始化报错后，分段兜底成功时应返回 0。`n$retryOutput"
    Assert-True (([regex]::Matches($retryCalls, '(?m)^ARGS:')).Count -eq 2) '全量初始化报错后应只执行一次分段兜底。'
    Assert-True ($retrySql.Contains('SPLIT_FALLBACK_ONLY')) '全量初始化报错后，未执行分段兜底脚本。'
    Assert-True ($retryOutput -match '兜底') '全量初始化报错后，输出中应明确提示正在执行兜底。'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'run\database-init-combined.sql'))) '报错兜底成功后未清理临时组合 SQL。'

    Remove-Item Env:FAKE_MYSQL_FAIL_FIRST_MARKER
    Remove-Item -LiteralPath $callLog, $stdinLog -Force
    $env:FAKE_MYSQL_SHOULD_FAIL = '1'
    $failureOutput = (& $packageBat 2>&1 | Out-String)
    $failureExitCode = $LASTEXITCODE

    Assert-True ($failureExitCode -ne 0) 'MySQL 执行失败时脚本不应返回 0。'
    Assert-True ($failureOutput -match '\[ERROR\]') '失败输出缺少 [ERROR] 标记。'
    Assert-True ($failureOutput -notmatch '\[OK\]') 'MySQL 执行失败后不应输出成功标记。'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'run\database-init-combined.sql'))) '失败后未清理临时组合 SQL。'

    Write-Host '[PASS] init-database.bat 客户交付场景测试通过。'
}
finally {
    Remove-Item Env:MYSQL_EXE_OVERRIDE -ErrorAction SilentlyContinue
    Remove-Item Env:FAKE_MYSQL_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:FAKE_MYSQL_STDIN -ErrorAction SilentlyContinue
    Remove-Item Env:FAKE_MYSQL_SHOULD_FAIL -ErrorAction SilentlyContinue
    Remove-Item Env:FAKE_MYSQL_FAIL_FIRST_MARKER -ErrorAction SilentlyContinue
    Remove-Item Env:BACKUP_INIT_NO_PAUSE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$global:LASTEXITCODE = 0
