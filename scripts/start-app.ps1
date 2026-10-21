[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('mysql', 'redis', 'caffeine')][string]$CacheMode = 'caffeine',
    [ValidateRange(1, 86400)][int]$OrderTtlSeconds = 60,
    [ValidateRange(1, 18)][int]$CloseDelayLevel = 5
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'target/linxiang-life-0.0.1-SNAPSHOT.jar'
$runtimePath = Join-Path $projectRoot 'target/runtime'
$pidPath = Join-Path $runtimePath 'app-process.json'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '未找到项目 JAR，请先在项目根目录运行 mvn clean package。' }
if (-not $JavaHome) { throw '请设置 JAVA_HOME，或传入 -JavaHome 指向 Java 8 JDK 目录。' }
$javaPath = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaPath)) { throw "未找到 Java：$javaPath" }
if (Get-NetTCPConnection -State Listen -LocalPort 18081 -ErrorAction SilentlyContinue) {
    throw '18081 端口已被使用。本脚本不会停止现有进程，请先确认并停止之前的本项目实例。'
}
if (Test-Path -LiteralPath $pidPath) {
    $previous = Get-Content -LiteralPath $pidPath -Raw | ConvertFrom-Json
    $previousProcess = Get-Process -Id ([int]$previous.processId) -ErrorAction SilentlyContinue
    if ($previousProcess) { throw '本项目 PID 记录仍指向一个运行进程，请先执行 scripts/stop-app.ps1。' }
}
New-Item -ItemType Directory -Force -Path $runtimePath | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $runtimePath "$stamp-$CacheMode.out.log"
$stderr = Join-Path $runtimePath "$stamp-$CacheMode.err.log"
$arguments = @('-Xms128m', '-Xmx512m', '-Dfile.encoding=UTF-8', '-jar', ('"' + $jarPath + '"'),
    '--spring.profiles.active=demo', '--server.address=127.0.0.1', '--server.port=18081',
    "--app.voucher-cache.mode=$CacheMode", "--app.order.ttl-seconds=$OrderTtlSeconds", "--app.order.close-delay-level=$CloseDelayLevel")
$appProcess = Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $projectRoot `
    -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
[ordered]@{
    processId = $appProcess.Id; jarPath = $jarPath; javaPath = $javaPath
    startedAt = $appProcess.StartTime.ToUniversalTime().ToString('o')
    cacheMode = $CacheMode; orderTtlSeconds = $OrderTtlSeconds; closeDelayLevel = $CloseDelayLevel
    stdout = $stdout; stderr = $stderr
} | ConvertTo-Json | Set-Content -LiteralPath $pidPath -Encoding UTF8
Write-Output "已启动本项目 Java PID $($appProcess.Id)，等待 http://127.0.0.1:18081/demo/status ..."
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    $appProcess.Refresh()
    if ($appProcess.HasExited) {
        Get-Content -LiteralPath $stderr -Tail 30 -ErrorAction SilentlyContinue
        throw "应用启动失败，查看 $stdout"
    }
    try {
        $status = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/demo/status' -TimeoutSec 2
        if ($status.success -eq $true) {
            Write-Output "HTTP 就绪；mode=$CacheMode；TTL=$OrderTtlSeconds；日志=$stdout"
            return
        }
    } catch { }
    Start-Sleep -Seconds 1
}
throw "等待 HTTP 就绪超时；进程仍保留用于检查，日志：$stdout"
