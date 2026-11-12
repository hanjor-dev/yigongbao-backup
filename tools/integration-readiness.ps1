param(
  [switch]$WriteProbe
)

$ErrorActionPreference = 'Stop'

function Require-Environment([string]$Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) { throw "缺少环境变量 $Name" }
  return $value
}

function Test-Service([string]$Name, [string]$BaseUrl) {
  $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health')
  if ($response.StatusCode -ne 200) { throw "$Name 健康检查失败：HTTP $($response.StatusCode)" }
  Write-Host "[OK] $Name $BaseUrl"
}

function Test-Storage([string]$Name, [string]$Path, [bool]$AllowWrite) {
  $resolved = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw "$Name 路径不存在：$resolved" }
  Get-ChildItem -LiteralPath $resolved -Force -ErrorAction Stop | Select-Object -First 1 | Out-Null
  if ($AllowWrite) {
    $probe = Join-Path $resolved ('.yigongbao-it-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $probe | Out-Null
    try {
      $file = Join-Path $probe 'probe.bin'
      [System.IO.File]::WriteAllBytes($file, [byte[]](1,2,3,4))
      if ((Get-Item -LiteralPath $file).Length -ne 4) { throw "$Name 写入校验失败" }
      Move-Item -LiteralPath $file -Destination (Join-Path $probe 'probe-moved.bin')
    } finally {
      if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Recurse -Force }
    }
  }
  Write-Host "[OK] $Name $resolved"
}

$instanceA = Require-Environment 'BACKUP_IT_BASE_URL_A'
$instanceB = Require-Environment 'BACKUP_IT_BASE_URL_B'
$localPath = Require-Environment 'BACKUP_IT_LOCAL_PATH'
Test-Service '实例A' $instanceA
Test-Service '实例B' $instanceB
Test-Storage 'Windows本地卷' $localPath $WriteProbe.IsPresent

$nasPath = [Environment]::GetEnvironmentVariable('BACKUP_IT_NAS_PATH')
if (-not [string]::IsNullOrWhiteSpace($nasPath)) { Test-Storage 'NAS/UNC卷' $nasPath $WriteProbe.IsPresent }
else { Write-Host '[SKIP] 未配置 BACKUP_IT_NAS_PATH' }

$cosProbe = [Environment]::GetEnvironmentVariable('BACKUP_IT_COS_PROBE_URL')
if (-not [string]::IsNullOrWhiteSpace($cosProbe)) {
  $response = Invoke-WebRequest -UseBasicParsing -Method Head -TimeoutSec 15 -Uri $cosProbe
  if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { throw "COS 只读探测失败：HTTP $($response.StatusCode)" }
  Write-Host '[OK] COS只读探测'
} else { Write-Host '[SKIP] 未配置 BACKUP_IT_COS_PROBE_URL' }

Write-Host '真实环境就绪检查完成。'
