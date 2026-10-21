[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$expectedJar = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target/linxiang-life-0.0.1-SNAPSHOT.jar'))
$pidPath = Join-Path $projectRoot 'target/runtime/app-process.json'
if (-not (Test-Path -LiteralPath $pidPath)) { Write-Output '没有本脚本管理的应用 PID 记录。'; return }
$record = Get-Content -LiteralPath $pidPath -Raw | ConvertFrom-Json
$appPid = [int]$record.processId
if ([IO.Path]::GetFullPath([string]$record.jarPath) -ine $expectedJar) { throw 'PID 文件的 JAR 路径不属于当前项目，拒绝停止。' }
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $appPid"
if (-not $processInfo) {
    Remove-Item -LiteralPath $pidPath
    Write-Output '记录中的进程已经退出，已清理过期 PID 记录。'
    return
}
if ($processInfo.Name -notin @('java.exe', 'javaw.exe') -or
    -not $processInfo.CommandLine -or
    $processInfo.CommandLine.IndexOf($expectedJar, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw 'PID 当前进程的命令行不包含本项目完整 JAR 路径，拒绝停止其他进程。'
}
$recordStart = ([DateTimeOffset]$record.startedAt).UtcDateTime
if ([Math]::Abs(($processInfo.CreationDate.ToUniversalTime() - $recordStart).TotalSeconds) -gt 2) {
    throw '进程启动时间与 PID 记录不符，可能发生 PID 复用，拒绝停止。'
}
Stop-Process -Id $appPid -ErrorAction Stop
Wait-Process -Id $appPid -Timeout 10 -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidPath
Write-Output "已停止本项目 Java PID $appPid；其他 Java 和 Docker 进程未操作。"
