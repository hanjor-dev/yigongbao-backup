[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $SkipFrontendBuild,
    [string] $JarPath,
    [string] $FrontendDistPath,
    [string] $NginxArchivePath,
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $moduleRoot
$frontendRoot = Join-Path $repositoryRoot 'yigongbao-backup-web'
$packageName = 'yigongbao-backup-server-windows'
$nginxVersion = '1.31.8'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $moduleRoot "dist\$packageName"
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
$outputLeaf = Split-Path -Leaf $resolvedOutput
$outputRoot = [IO.Path]::GetPathRoot($resolvedOutput)
if ($outputLeaf -ne $packageName -or $resolvedOutput -eq $outputRoot -or $resolvedOutput -eq [IO.Path]::GetFullPath($moduleRoot)) {
    throw "拒绝使用不安全的客户包输出目录：$resolvedOutput"
}

if (-not $SkipBuild) {
    Write-Host '[1/8] 使用 Maven 构建后端 JAR...'
    Push-Location $moduleRoot
    try {
        & mvn.cmd clean package
        if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码：$LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Host '[1/8] 已跳过 Maven 构建。'
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    [xml] $pom = Get-Content -Raw -LiteralPath (Join-Path $moduleRoot 'pom.xml')
    $artifactId = [string] $pom.project.artifactId
    $version = [string] $pom.project.version
    $JarPath = Join-Path $moduleRoot "target\$artifactId-$version.jar"
}
$resolvedJar = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) {
    throw "未找到后端 JAR：$resolvedJar"
}
if ((Get-Item -LiteralPath $resolvedJar).Length -le 0) {
    throw "后端 JAR 为空：$resolvedJar"
}
& (Join-Path $PSScriptRoot 'test-executable-jar.ps1') -JarPath $resolvedJar
if ($LASTEXITCODE -ne 0) {
    throw "后端 JAR 可执行性校验失败，退出码：$LASTEXITCODE"
}

if (-not $SkipFrontendBuild) {
    Write-Host '[2/8] 安装前端依赖并构建生产版本...'
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'package-lock.json') -PathType Leaf)) {
        throw "未找到前端 package-lock.json：$frontendRoot"
    }
    Push-Location $frontendRoot
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "前端 npm ci 失败，退出码：$LASTEXITCODE" }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "前端生产构建失败，退出码：$LASTEXITCODE" }
    }
    finally { Pop-Location }
}
else {
    Write-Host '[2/8] 已跳过前端构建。'
}

if ([string]::IsNullOrWhiteSpace($FrontendDistPath)) {
    $FrontendDistPath = Join-Path $frontendRoot 'dist'
}
$resolvedFrontendDist = [IO.Path]::GetFullPath($FrontendDistPath)
if (-not (Test-Path -LiteralPath (Join-Path $resolvedFrontendDist 'index.html') -PathType Leaf)) {
    throw "前端构建产物无效，缺少 index.html：$resolvedFrontendDist"
}

if ([string]::IsNullOrWhiteSpace($NginxArchivePath)) {
    $downloadDirectory = Join-Path $moduleRoot 'target\downloads'
    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    $NginxArchivePath = Join-Path $downloadDirectory "nginx-$nginxVersion.zip"
    if (-not (Test-Path -LiteralPath $NginxArchivePath -PathType Leaf)) {
        Write-Host "[3/8] 从 nginx.org 下载 Nginx/Windows $nginxVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://nginx.org/download/nginx-$nginxVersion.zip" `
            -OutFile $NginxArchivePath
    }
    else {
        Write-Host "[3/8] 使用已缓存的 Nginx/Windows $nginxVersion。"
    }
}
else {
    Write-Host '[3/8] 使用指定的 Nginx 压缩包。'
}
$resolvedNginxArchive = [IO.Path]::GetFullPath($NginxArchivePath)
if (-not (Test-Path -LiteralPath $resolvedNginxArchive -PathType Leaf)) {
    throw "未找到 Nginx 压缩包：$resolvedNginxArchive"
}

$parentDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $parentDirectory -Force | Out-Null
$stagingDirectory = Join-Path $parentDirectory ('.customer-package-staging-' + [Guid]::NewGuid().ToString('N'))
$nginxExtractDirectory = Join-Path $parentDirectory ('.nginx-extract-' + [Guid]::NewGuid().ToString('N'))

try {
    Write-Host '[4/8] 整理后端、前端、Nginx、配置和初始化 SQL...'
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'sql\init') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'frontend') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'nginx\conf') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'nginx\logs') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'nginx\temp\client_body_temp') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'nginx\temp\proxy_temp') -Force | Out-Null

    Copy-Item -LiteralPath $resolvedJar -Destination (Join-Path $stagingDirectory 'yigongbao-backup-server.jar')
    foreach ($scriptName in @('start-backup.bat', 'stop-backup.bat', 'init-database.bat',
            'start-nginx.bat', 'restart-nginx.bat', 'stop-nginx.bat', 'check-status.bat')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $scriptName) -Destination (Join-Path $stagingDirectory $scriptName)
    }
    $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
    foreach ($batFile in Get-ChildItem -LiteralPath $stagingDirectory -Filter '*.bat') {
        $batContent = [IO.File]::ReadAllText($batFile.FullName, $utf8WithoutBom)
        $batContent = ($batContent -replace "`r`n", "`n") -replace "`r", "`n"
        $batContent = $batContent -replace "`n", "`r`n"
        [IO.File]::WriteAllText($batFile.FullName, $batContent, $utf8WithoutBom)
    }

    $environmentTemplate = Join-Path $moduleRoot '.env.example'
    $existingEnvironment = Join-Path $resolvedOutput '.env'
    if (Test-Path -LiteralPath $existingEnvironment -PathType Leaf) {
        Copy-Item -LiteralPath $existingEnvironment -Destination (Join-Path $stagingDirectory '.env')
        Write-Host '      已保留固定交付目录中现有的 .env 配置。'
    }
    else {
        Copy-Item -LiteralPath $environmentTemplate -Destination (Join-Path $stagingDirectory '.env')
    }
    Copy-Item -LiteralPath $environmentTemplate -Destination (Join-Path $stagingDirectory '.env.example')

    Copy-Item -LiteralPath $resolvedFrontendDist -Destination (Join-Path $stagingDirectory 'frontend\dist') -Recurse

    Expand-Archive -LiteralPath $resolvedNginxArchive -DestinationPath $nginxExtractDirectory -Force
    $nginxExecutable = Get-ChildItem -LiteralPath $nginxExtractDirectory -Filter 'nginx.exe' -File -Recurse |
        Select-Object -First 1
    if ($null -eq $nginxExecutable) { throw "Nginx 压缩包中缺少 nginx.exe：$resolvedNginxArchive" }
    $nginxSourceRoot = $nginxExecutable.Directory.FullName
    $mimeTypesPath = Join-Path $nginxSourceRoot 'conf\mime.types'
    if (-not (Test-Path -LiteralPath $mimeTypesPath -PathType Leaf)) {
        throw "Nginx 压缩包中缺少 conf\mime.types：$resolvedNginxArchive"
    }
    Copy-Item -LiteralPath $nginxExecutable.FullName -Destination (Join-Path $stagingDirectory 'nginx\nginx.exe')
    Copy-Item -LiteralPath $mimeTypesPath -Destination (Join-Path $stagingDirectory 'nginx\conf\mime.types')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'customer-package\nginx\conf\nginx.conf') `
        -Destination (Join-Path $stagingDirectory 'nginx\conf\nginx.conf')
    $nginxLicense = Join-Path $nginxSourceRoot 'docs\text\LICENSE'
    if (Test-Path -LiteralPath $nginxLicense -PathType Leaf) {
        Copy-Item -LiteralPath $nginxLicense -Destination (Join-Path $stagingDirectory 'nginx\LICENSE.txt')
    }

    $sqlFiles = @(
        'yigongbao-backup-full-init.sql',
        '00-create-database.sql',
        '01-schema.sql',
        '02-indexes.sql',
        '03-base-version.sql',
        '04-functional-config-defaults.sql'
    )
    foreach ($sqlFile in $sqlFiles) {
        Copy-Item -LiteralPath (Join-Path $moduleRoot "sql\init\$sqlFile") `
            -Destination (Join-Path $stagingDirectory "sql\init\$sqlFile")
    }

    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'customer-package\README-请先阅读.md') `
        -Destination (Join-Path $stagingDirectory 'README-请先阅读.md')

    Write-Host '[5/8] 生成版本信息...'
    [xml] $projectPom = Get-Content -Raw -LiteralPath (Join-Path $moduleRoot 'pom.xml')
    $projectVersion = [string] $projectPom.project.version
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stagingDirectory 'yigongbao-backup-server.jar')).Hash
    $versionText = @"
医工宝备份系统 Windows 前后端交付包
版本：$projectVersion
构建时间：$([DateTimeOffset]::Now.ToString('yyyy-MM-dd HH:mm:ss zzz'))
运行环境：Java 21、MySQL 8.0/8.2、Nginx/Windows $nginxVersion、Windows 64 位
JAR SHA256：$jarHash
"@
    [IO.File]::WriteAllText((Join-Path $stagingDirectory '版本信息.txt'), $versionText,
        (New-Object Text.UTF8Encoding($false)))

    Write-Host '[6/8] 生成 SHA256 文件校验清单...'
    $checksumLines = Get-ChildItem -LiteralPath $stagingDirectory -Recurse -File -Force |
        Sort-Object FullName |
        ForEach-Object {
            $relativePath = [IO.Path]::GetRelativePath($stagingDirectory, $_.FullName)
            $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
            "$hash  $relativePath"
        }
    [IO.File]::WriteAllLines((Join-Path $stagingDirectory 'SHA256校验.txt'), $checksumLines,
        (New-Object Text.UTF8Encoding($false)))

    Write-Host '[7/8] 发布固定客户交付目录...'
    if (Test-Path -LiteralPath $resolvedOutput) {
        # 保留固定目录本身，避免资源管理器或终端正在查看该目录时 Windows 拒绝发布。
        Get-ChildItem -LiteralPath $resolvedOutput -Force | Remove-Item -Recurse -Force
        Get-ChildItem -LiteralPath $stagingDirectory -Force | Move-Item -Destination $resolvedOutput
        Remove-Item -LiteralPath $stagingDirectory -Force
    }
    else {
        Move-Item -LiteralPath $stagingDirectory -Destination $resolvedOutput
    }
    Write-Host '[8/8] 客户包整理完成。'
    Write-Host "[OK] 客户交付包已生成：$resolvedOutput"
}
finally {
    if (Test-Path -LiteralPath $stagingDirectory) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
    if (Test-Path -LiteralPath $nginxExtractDirectory) {
        Remove-Item -LiteralPath $nginxExtractDirectory -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
