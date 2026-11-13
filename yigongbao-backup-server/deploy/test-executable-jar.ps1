param(
    [string] $JarPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'target\yigongbao-backup-server-1.0.0.jar')
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    if (-not $Condition) { throw $Message }
}

$resolvedJar = [IO.Path]::GetFullPath($JarPath)
Assert-True (Test-Path -LiteralPath $resolvedJar -PathType Leaf) "JAR 不存在：$resolvedJar"

$archive = [IO.Compression.ZipFile]::OpenRead($resolvedJar)
try {
    $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
    Assert-True ($null -ne $manifestEntry) 'JAR 缺少 META-INF/MANIFEST.MF。'
    $reader = New-Object IO.StreamReader($manifestEntry.Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }

    Assert-True ($manifest -match '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\s*$') `
        'JAR 不是 Spring Boot 可执行包：缺少 JarLauncher Main-Class。'
    Assert-True ($manifest -match '(?m)^Start-Class: com\.yigongbao\.backup\.BackupServerApplication\s*$') `
        'JAR 启动类不是 BackupServerApplication。'
    Assert-True ($null -ne $archive.GetEntry('BOOT-INF/classes/com/yigongbao/backup/BackupServerApplication.class')) `
        'JAR 缺少 BOOT-INF/classes 下的应用启动类。'
    Assert-True (@($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' }).Count -gt 0) `
        'JAR 未包含运行依赖 BOOT-INF/lib。'
}
finally {
    $archive.Dispose()
}

Write-Host "[PASS] Spring Boot 可执行 JAR 校验通过：$resolvedJar"
$global:LASTEXITCODE = 0
