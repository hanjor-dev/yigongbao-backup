$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    if (-not $Condition) { throw $Message }
}

$moduleRoot = Split-Path -Parent $PSScriptRoot
$packager = Join-Path $PSScriptRoot 'build-customer-package.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('yigongbao-package-test-' + [Guid]::NewGuid().ToString('N'))
$fakeJar = Join-Path $testRoot 'fake-server.jar'
$thinJar = Join-Path $testRoot 'thin-server.jar'
$fakeFrontendDist = Join-Path $testRoot 'frontend-dist'
$fakeNginxRoot = Join-Path $testRoot 'nginx-1.31.8'
$fakeNginxArchive = Join-Path $testRoot 'nginx-1.31.8.zip'
$outputDirectory = Join-Path $testRoot 'yigongbao-backup-server-windows'

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $archive = [IO.Compression.ZipFile]::Open($fakeJar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $manifestEntry = $archive.CreateEntry('META-INF/MANIFEST.MF')
        $writer = New-Object IO.StreamWriter($manifestEntry.Open())
        try {
            $writer.Write("Manifest-Version: 1.0`r`nMain-Class: org.springframework.boot.loader.launch.JarLauncher`r`nStart-Class: com.yigongbao.backup.BackupServerApplication`r`n`r`n")
        }
        finally { $writer.Dispose() }
        [void] $archive.CreateEntry('BOOT-INF/classes/com/yigongbao/backup/BackupServerApplication.class')
        [void] $archive.CreateEntry('BOOT-INF/lib/spring-boot.jar')
    }
    finally { $archive.Dispose() }

    $thinArchive = [IO.Compression.ZipFile]::Open($thinJar, [IO.Compression.ZipArchiveMode]::Create)
    try { [void] $thinArchive.CreateEntry('META-INF/MANIFEST.MF') } finally { $thinArchive.Dispose() }

    New-Item -ItemType Directory -Path (Join-Path $fakeFrontendDist 'assets') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fakeFrontendDist 'index.html') `
        -Value '<!doctype html><title>医工宝本地备份管理</title>' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $fakeFrontendDist 'assets\app.js') `
        -Value 'console.log("frontend-ready")' -Encoding utf8

    New-Item -ItemType Directory -Path (Join-Path $fakeNginxRoot 'conf') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fakeNginxRoot 'nginx.exe') -Value 'fake-nginx' -Encoding ascii
    Set-Content -LiteralPath (Join-Path $fakeNginxRoot 'conf\mime.types') `
        -Value 'types { text/html html; application/javascript js; text/css css; }' -Encoding ascii
    Compress-Archive -LiteralPath $fakeNginxRoot -DestinationPath $fakeNginxArchive

    Assert-True (Test-Path -LiteralPath $packager) 'build-customer-package.ps1 不存在。'
    & $packager -SkipBuild -SkipFrontendBuild -JarPath $fakeJar `
        -FrontendDistPath $fakeFrontendDist -NginxArchivePath $fakeNginxArchive `
        -OutputDirectory $outputDirectory
    Assert-True ($LASTEXITCODE -eq 0) "打包脚本退出码不是 0：$LASTEXITCODE"

    $requiredFiles = @(
        'yigongbao-backup-server.jar',
        'start-backup.bat',
        'stop-backup.bat',
        'init-database.bat',
        'start-nginx.bat',
        'restart-nginx.bat',
        'stop-nginx.bat',
        'check-status.bat',
        '.env',
        '.env.example',
        'README-请先阅读.md',
        '版本信息.txt',
        'SHA256校验.txt',
        'sql\init\yigongbao-backup-full-init.sql',
        'sql\init\00-create-database.sql',
        'sql\init\01-schema.sql',
        'sql\init\02-indexes.sql',
        'sql\init\03-base-version.sql',
        'sql\init\04-functional-config-defaults.sql',
        'frontend\dist\index.html',
        'frontend\dist\assets\app.js',
        'nginx\nginx.exe',
        'nginx\conf\nginx.conf',
        'nginx\conf\mime.types'
    )
    foreach ($relativePath in $requiredFiles) {
        Assert-True (Test-Path -LiteralPath (Join-Path $outputDirectory $relativePath)) "客户包缺少：$relativePath"
    }
    foreach ($batFile in Get-ChildItem -LiteralPath $outputDirectory -Filter '*.bat') {
        $batContent = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($batFile.FullName))
        Assert-True ([regex]::Matches($batContent, "(?<!`r)`n").Count -eq 0) "客户 BAT 必须使用 CRLF：$($batFile.Name)"
    }

    # 客户可能正通过资源管理器或终端查看固定交付目录；重复构建不得依赖删除目录本身。
    Set-Content -LiteralPath (Join-Path $outputDirectory 'stale-file.tmp') -Value 'old'
    Push-Location $outputDirectory
    try {
        & $packager -SkipBuild -SkipFrontendBuild -JarPath $fakeJar `
            -FrontendDistPath $fakeFrontendDist -NginxArchivePath $fakeNginxArchive `
            -OutputDirectory $outputDirectory
    }
    finally { Pop-Location }
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $outputDirectory 'stale-file.tmp'))) `
        '重复构建必须清理旧交付内容。'
    Assert-True (Test-Path -LiteralPath (Join-Path $outputDirectory 'yigongbao-backup-server.jar')) `
        '交付目录被查看时重复构建仍应成功。'

    $unexpectedFiles = @(
        'install-windows-service.ps1',
        'test-customer-bat.ps1',
        'test-init-database-bat.ps1'
    )
    foreach ($relativePath in $unexpectedFiles) {
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $outputDirectory $relativePath))) "客户包不应包含：$relativePath"
    }

    $packagedJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputDirectory 'yigongbao-backup-server.jar')).Hash
    $sourceJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $fakeJar).Hash
    Assert-True ($packagedJarHash -eq $sourceJarHash) '客户包中的 JAR 与构建产物不一致。'
    $checksums = Get-Content -Raw -LiteralPath (Join-Path $outputDirectory 'SHA256校验.txt')
    Assert-True ($checksums.Contains($packagedJarHash)) 'SHA256 校验文件缺少 JAR 摘要。'

    $sourceEnv = Get-Content -Raw -LiteralPath (Join-Path $moduleRoot '.env.example')
    $packagedEnv = Get-Content -Raw -LiteralPath (Join-Path $outputDirectory '.env')
    Assert-True ($packagedEnv -eq $sourceEnv) '客户包 .env 必须来自安全模板，不能复制本地 .env。'
    Assert-True ($packagedEnv.Contains('127.0.0.1:3306/yigongbao_backup')) '生产模板应默认使用 MySQL 标准端口 3306。'
    Assert-True (-not $packagedEnv.Contains(':3307/')) '客户包不应保留本地测试端口 3307。'
    Assert-True ($packagedEnv.Contains('BACKUP_DB_USERNAME=yigongbao_backup_app')) '生产模板应使用明确的应用数据库账号占位名。'
    Assert-True ($packagedEnv.Contains('BACKUP_EXECUTION_MODE=REAL')) '客户包必须默认使用 REAL 模式。'
    Assert-True ($packagedEnv -match '(?m)^BACKUP_SIMULATION_ROOT=$') '生产模板的模拟目录必须为空。'

    foreach ($sqlFile in $requiredFiles | Where-Object { $_ -like 'sql\init\*.sql' }) {
        $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $moduleRoot $sqlFile)).Hash
        $packageHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputDirectory $sqlFile)).Hash
        Assert-True ($sourceHash -eq $packageHash) "初始化 SQL 内容不一致：$sqlFile"
    }

    $fullInitSql = Get-Content -Raw -LiteralPath (Join-Path $outputDirectory 'sql\init\yigongbao-backup-full-init.sql')
    foreach ($indexName in @('idx_bk_volume_archive_phase', 'idx_bk_volume_archive_item_progress')) {
        Assert-True ($fullInitSql.Contains($indexName)) "全量初始化 SQL 缺少索引：$indexName"
    }

    $readme = Get-Content -Raw -LiteralPath (Join-Path $outputDirectory 'README-请先阅读.md')
    foreach ($marker in @('Java 21', 'MySQL 8', 'init-database.bat', 'start-backup.bat',
            'stop-backup.bat', 'start-nginx.bat', 'restart-nginx.bat', 'stop-nginx.bat',
            'nginx.conf', 'frontend\dist', 'BACKUP_SHARED_HMAC_SECRET', 'online.base-url', 'log')) {
        Assert-True ($readme.Contains($marker)) "客户说明缺少关键信息：$marker"
    }

    $topLevelNames = @(Get-ChildItem -LiteralPath $outputDirectory -Force | ForEach-Object Name)
    Assert-True ($topLevelNames -contains 'frontend') '客户包必须包含前端构建产物。'
    Assert-True ($topLevelNames -contains 'nginx') '客户包必须包含 Nginx Windows 运行文件。'

    $thinRejected = $false
    try {
        & $packager -SkipBuild -JarPath $thinJar `
            -OutputDirectory (Join-Path $testRoot 'thin\yigongbao-backup-server-windows')
    }
    catch {
        $thinRejected = $true
    }
    Assert-True $thinRejected '打包器必须拒绝缺少 Spring Boot 依赖和启动入口的薄 JAR。'

    Write-Host '[PASS] Windows 前后端客户交付包结构测试通过。'
}
finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$global:LASTEXITCODE = 0
