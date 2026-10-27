param(
  [string]$ServiceName = "YiGongBaoBackupServer",
  [string]$JavaPath = "C:\Program Files\Java\jdk-21\bin\java.exe",
  [string]$JarPath = "C:\YiGongBao\backup\yigongbao-backup-server.jar",
  [string]$WorkDir = "C:\YiGongBao\backup"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $JavaPath)) { throw "Java 21 不存在: $JavaPath" }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "服务包不存在: $JarPath" }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$bin = '"{0}" -jar "{1}" --spring.profiles.active=prod' -f $JavaPath,$JarPath
if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) { Stop-Service $ServiceName -ErrorAction SilentlyContinue; sc.exe delete $ServiceName | Out-Null }
New-Service -Name $ServiceName -BinaryPathName $bin -DisplayName "医工宝本地备份服务" -StartupType Automatic -Description "医工宝 COS 本地热备、冷备与恢复服务"
Start-Service $ServiceName
Write-Host "已注册并启动 $ServiceName；请使用 Get-Service $ServiceName 检查状态。"
