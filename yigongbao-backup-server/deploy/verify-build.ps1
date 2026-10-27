$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path (Split-Path -Parent $root) "yigongbao-backup-web"
Write-Host "[1/4] backend tests"
Push-Location $root
try { mvn clean test } finally { Pop-Location }
Write-Host "[2/4] frontend build"
Push-Location $webRoot
try { npm run build } finally { Pop-Location }
Write-Host "[3/4] required artifacts"
$required = @(
  "src/main/resources/openapi/local-backup-v1.yaml",
  "src/main/resources/schema/manifest-v1.schema.json",
  "sql/init/00-create-database.sql",
  "sql/init/01-schema.sql",
  "sql/init/02-indexes.sql",
  "deploy/install-windows-service.ps1",
  "src/main/resources/application-prod.yml"
)
foreach ($file in $required) { if (-not (Test-Path (Join-Path $root $file))) { throw "missing artifact: $file" } }
if (-not (Test-Path (Join-Path $webRoot "package.json"))) { throw "missing frontend package.json" }
Write-Host "[4/4] verification passed"
