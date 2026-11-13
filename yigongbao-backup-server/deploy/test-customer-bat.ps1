$ErrorActionPreference = "Stop"

$deployDir = $PSScriptRoot
$startScript = Join-Path $deployDir "start-backup.bat"
$stopScript = Join-Path $deployDir "stop-backup.bat"

if (-not (Test-Path -LiteralPath $startScript)) {
    throw "缺少待测启动脚本: $startScript"
}
if (-not (Test-Path -LiteralPath $stopScript)) {
    throw "缺少待测停止脚本: $stopScript"
}

function Wait-Until {
    param(
        [Parameter(Mandatory)] [scriptblock] $Condition,
        [Parameter(Mandatory)] [string] $FailureMessage,
        [int] $TimeoutSeconds = 20
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)

    throw $FailureMessage
}

function Test-OutputContainsText {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Expected
    )
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $bytes = New-Object byte[] $stream.Length
        [void] $stream.Read($bytes, 0, $bytes.Length)
    }
    finally { $stream.Dispose() }
    $encodings = @(
        (New-Object Text.UTF8Encoding($false)),
        [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage),
        [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
    )
    foreach ($encoding in $encodings) {
        if ($encoding.GetString($bytes).Contains($Expected)) { return $true }
    }
    return $false
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process] $Process)
    if ($null -eq $Process -or $Process.HasExited) { return }
    & taskkill.exe /PID $Process.Id /T /F *> $null
}

$javaCommand = Get-Command java.exe -ErrorAction Stop
$javaSettings = & $javaCommand.Source -XshowSettings:properties -version 2>&1
$javaHomeMatch = $javaSettings | Select-String '^\s*java.home\s*=\s*(?<home>.+)$' | Select-Object -First 1
if ($null -eq $javaHomeMatch) {
    throw "无法从 Java 运行时信息中识别 java.home"
}
$javaHome = $javaHomeMatch.Matches[0].Groups['home'].Value.Trim()
$javaMajorLine = & $javaCommand.Source -version 2>&1 | Select-Object -First 1
if ($javaMajorLine -notmatch 'version\s+"(?<version>[^"]+)"') {
    throw "无法识别测试机 Java 版本: $javaMajorLine"
}
$javaMajor = [int](($Matches.version -split '\.')[0])

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("YiGongBao BAT Test " + [Guid]::NewGuid().ToString("N"))
$monitorOne = $null
$monitorTwo = $null
$backendPid = $null

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    Copy-Item -LiteralPath $startScript -Destination (Join-Path $testRoot "start-backup.bat")
    Copy-Item -LiteralPath $stopScript -Destination (Join-Path $testRoot "stop-backup.bat")

    $copiedStart = Join-Path $testRoot "start-backup.bat"
    $startContent = Get-Content -Raw -LiteralPath $copiedStart
    $startContent = $startContent -replace '(?m)^set "JAVA_HOME_CUSTOM=.*"$', ('set "JAVA_HOME_CUSTOM=' + $javaHome + '"')
    $startContent = $startContent -replace '(?m)^set "REQUIRED_JAVA_MAJOR=.*"$', ('set "REQUIRED_JAVA_MAJOR=' + $javaMajor + '"')
    Set-Content -LiteralPath $copiedStart -Value $startContent -Encoding utf8

    $probeSource = @'
import java.nio.charset.StandardCharsets;
import java.nio.channels.Selector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Probe {
    public static void main(String[] args) throws Exception {
        try (Selector ignored = Selector.open()) {
            // 验证客户环境能够创建 Tomcat NIO 所需的 Selector。
        }
        Path run = Path.of(System.getProperty("user.dir"), "run");
        Files.createDirectories(run);
        Files.writeString(run.resolve("probe-start-count.txt"), "started\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(run.resolve("probe-java-home.txt"), System.getProperty("java.home"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(run.resolve("probe-unixdomain-tmpdir.txt"), System.getProperty("jdk.net.unixdomain.tmpdir", ""), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        while (true) {
            System.out.write("PROBE_HEARTBEAT 中文日志编码检查\n".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            Thread.sleep(200L);
        }
    }
}
'@
    Set-Content -LiteralPath (Join-Path $testRoot "Probe.java") -Value $probeSource -Encoding utf8
    & (Join-Path $javaHome "bin\javac.exe") -encoding UTF-8 (Join-Path $testRoot "Probe.java")
    if ($LASTEXITCODE -ne 0) { throw "测试探针编译失败" }
    & (Join-Path $javaHome "bin\jar.exe") --create --file (Join-Path $testRoot "yigongbao-backup-server.jar") --main-class Probe -C $testRoot "Probe.class"
    if ($LASTEXITCODE -ne 0) { throw "测试探针打包失败" }
    Set-Content -LiteralPath (Join-Path $testRoot ".env") -Value "SPRING_PROFILES_ACTIVE=prod" -Encoding ascii

    $monitorOneOut = Join-Path $testRoot "monitor-one.out.log"
    $monitorOneErr = Join-Path $testRoot "monitor-one.err.log"
    $monitorOne = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', 'start-backup.bat') -WorkingDirectory $testRoot -WindowStyle Hidden -RedirectStandardOutput $monitorOneOut -RedirectStandardError $monitorOneErr -PassThru
    $pidFile = Join-Path $testRoot "run\backup-server.pid"
    $consoleLog = Join-Path $testRoot "log\console.log"
    Wait-Until { Test-Path -LiteralPath $pidFile } "启动脚本未生成 PID 文件"
    $backendPid = [int](Get-Content -Raw -LiteralPath $pidFile).Trim()
    Wait-Until { (Test-Path -LiteralPath $consoleLog) -and ((Get-Content -Raw -LiteralPath $consoleLog) -match 'PROBE_HEARTBEAT') } "启动脚本未实时写入 Java 控制台日志"
    Wait-Until { Test-OutputContainsText -Path $monitorOneOut -Expected '中文日志编码检查' } `
        "实时日志窗口未按 UTF-8 正确显示中文日志"

    if (-not (Get-Process -Id $backendPid -ErrorAction SilentlyContinue)) {
        throw "PID 文件指向的后台进程不存在: $backendPid"
    }

    Stop-ProcessTree $monitorOne
    Start-Sleep -Milliseconds 800
    if (-not (Get-Process -Id $backendPid -ErrorAction SilentlyContinue)) {
        throw "关闭实时日志窗口后，后台 Java 进程被错误终止"
    }

    $monitorTwo = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', 'start-backup.bat') -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    $secondPid = [int](Get-Content -Raw -LiteralPath $pidFile).Trim()
    if ($secondPid -ne $backendPid) {
        throw "重复启动创建了新的后台进程: first=$backendPid second=$secondPid"
    }
    $startCount = @(Get-Content -LiteralPath (Join-Path $testRoot "run\probe-start-count.txt"))
    if ($startCount.Count -ne 1) {
        throw "重复启动实际拉起了 $($startCount.Count) 个 Java 探针"
    }
    Stop-ProcessTree $monitorTwo

    $actualJavaHome = (Get-Content -Raw -LiteralPath (Join-Path $testRoot "run\probe-java-home.txt")).TrimEnd('\')
    if (-not [IO.Path]::GetFullPath($actualJavaHome).Equals([IO.Path]::GetFullPath($javaHome).TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "启动脚本未使用 JAVA_HOME_CUSTOM 指定的 Java: $actualJavaHome"
    }
    $unixDomainTmpDir = (Get-Content -Raw -LiteralPath (Join-Path $testRoot "run\probe-unixdomain-tmpdir.txt")).Trim()
    if ([string]::IsNullOrWhiteSpace($unixDomainTmpDir)) {
        throw "启动脚本未设置 jdk.net.unixdomain.tmpdir，Windows 长临时目录可能导致 NIO Selector 启动失败"
    }

    $stop = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', 'stop-backup.bat --no-pause') -WorkingDirectory $testRoot -WindowStyle Hidden -Wait -PassThru
    if ($stop.ExitCode -ne 0) { throw "停止脚本返回失败退出码: $($stop.ExitCode)" }
    Wait-Until { -not (Get-Process -Id $backendPid -ErrorAction SilentlyContinue) } "停止脚本未终止后台进程"
    if (Test-Path -LiteralPath $pidFile) { throw "停止后 PID 文件仍然存在" }

    Write-Host "PASS: 客户 BAT 启动、日志分离、重复启动保护和停止行为均符合预期"
}
catch {
    Write-Host "BAT 验收失败，测试目录：$testRoot"
    foreach ($diagnostic in @("monitor-one.out.log", "monitor-one.err.log", "log\console.log", "run\backup-server.args")) {
        $path = Join-Path $testRoot $diagnostic
        if (Test-Path -LiteralPath $path) {
            Write-Host "--- $diagnostic ---"
            Get-Content -LiteralPath $path
        }
    }
    throw
}
finally {
    Stop-ProcessTree $monitorOne
    Stop-ProcessTree $monitorTwo
    if ($backendPid -and (Get-Process -Id $backendPid -ErrorAction SilentlyContinue)) {
        & taskkill.exe /PID $backendPid /T /F *> $null
        Wait-Until { -not (Get-Process -Id $backendPid -ErrorAction SilentlyContinue) } "清理测试探针超时" 5
    }
    if (Test-Path -LiteralPath $testRoot) {
        Start-Sleep -Milliseconds 300
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
