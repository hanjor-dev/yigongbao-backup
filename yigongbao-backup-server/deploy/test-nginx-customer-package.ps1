[CmdletBinding()]
param(
    [string] $PackageDirectory
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($PackageDirectory)) {
    $PackageDirectory = Join-Path $moduleRoot 'dist\yigongbao-backup-server-windows'
}
$packageRoot = [IO.Path]::GetFullPath($PackageDirectory)

function Invoke-CustomerScript {
    param([Parameter(Mandatory)] [string] $Name)
    $scriptPath = Join-Path $packageRoot $Name
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw "客户包缺少脚本：$Name"
    }
    $process = Start-Process -FilePath $env:ComSpec `
        -ArgumentList @('/d', '/c', "`"$scriptPath`" --no-pause") `
        -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit(60000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "$Name 在 60 秒内未退出。"
    }
    if ($process.ExitCode -ne 0) {
        throw "$Name 返回失败退出码：$($process.ExitCode)"
    }
}

function Wait-HttpStatus {
    param(
        [Parameter(Mandatory)] [string] $Uri,
        [Parameter(Mandatory)] [int[]] $ExpectedStatus,
        [string] $Method = 'GET',
        [string] $Body,
        [int] $TimeoutSeconds = 20
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $request = @{ UseBasicParsing = $true; SkipHttpErrorCheck = $true; Uri = $Uri; Method = $Method; TimeoutSec = 3 }
            if ($null -ne $Body) {
                $request.Body = $Body
                $request.ContentType = 'application/json'
            }
            $response = Invoke-WebRequest @request
            if ($ExpectedStatus -contains [int] $response.StatusCode) { return $response }
        }
        catch { }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "等待 HTTP 状态超时：$Uri，期望 $($ExpectedStatus -join ',')"
}

$nginxExe = Join-Path $packageRoot 'nginx\nginx.exe'
$nginxHome = Join-Path $packageRoot 'nginx'
$nginxConfig = Join-Path $nginxHome 'conf\nginx.conf'
foreach ($required in @($nginxExe, $nginxConfig, (Join-Path $packageRoot 'frontend\dist\index.html'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "客户包缺少：$required" }
}

try {
    & $nginxExe -t -p $nginxHome -c 'conf/nginx.conf'
    if ($LASTEXITCODE -ne 0) { throw "nginx -t 失败，退出码：$LASTEXITCODE" }

    Invoke-CustomerScript 'start-nginx.bat'
    $page = Wait-HttpStatus -Uri 'http://127.0.0.1/' -ExpectedStatus 200
    if (-not $page.Content.Contains('医工宝本地备份管理')) {
        throw 'Nginx 返回的不是本次构建的前端页面。'
    }
    [void] (Wait-HttpStatus -Uri 'http://127.0.0.1/nginx-health' -ExpectedStatus 200)
    $apiProbe = Wait-HttpStatus -Uri 'http://127.0.0.1/api/v1/auth/login' -Method 'POST' `
        -Body '{"username":"__nginx_probe__","password":"invalid-probe-password"}' -ExpectedStatus 400
    if (-not $apiProbe.Content.Contains('INVALID_ARGUMENT')) {
        throw 'API 代理探针未返回 Spring Boot 的业务错误信封。'
    }
    [void] (Wait-HttpStatus -Uri 'http://127.0.0.1/assets/not-found.js' -ExpectedStatus 404)

    Invoke-CustomerScript 'restart-nginx.bat'
    [void] (Wait-HttpStatus -Uri 'http://127.0.0.1/' -ExpectedStatus 200)

    Invoke-CustomerScript 'stop-nginx.bat'
    $pidFile = Join-Path $nginxHome 'logs\nginx.pid'
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ((Test-Path -LiteralPath $pidFile) -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 300
    }
    if (Test-Path -LiteralPath $pidFile) { throw '停止脚本执行后 nginx.pid 仍然存在。' }

    Write-Host '[PASS] Nginx 配置、前端页面、API 代理、重启和停止均通过实际运行验证。'
}
finally {
    if (Test-Path -LiteralPath (Join-Path $packageRoot 'stop-nginx.bat')) {
        try { Invoke-CustomerScript 'stop-nginx.bat' } catch { }
    }
}
