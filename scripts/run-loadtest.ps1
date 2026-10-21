[CmdletBinding()]
param(
    [ValidateSet('seckill', 'hot-read')][string]$Scenario = 'hot-read',
    [string]$JMeterHome = (Join-Path $env:LOCALAPPDATA 'LinxiangTools/apache-jmeter-5.6.3'),
    [string]$JavaHome = $env:JAVA_HOME,
    # 仅记录实验标签；服务端必须已经用对应 app.voucher-cache.mode 启动。
    [ValidateSet('mysql', 'redis', 'caffeine', 'unspecified')][string]$CacheMode = 'unspecified',
    [ValidateRange(1, 500)][int]$Threads = 20,
    [ValidateRange(1, 10000)][int]$Loops = 20,
    [ValidateRange(0, 3600)][int]$RampSeconds = 2,
    [ValidateRange(1, 65535)][int]$Port = 18081,
    [long]$VoucherId = 101,
    [ValidateRange(0, 99999000)][long]$UserBase = 10000000,
    [string]$Path = '/voucher/list/1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jmeter = Join-Path $JMeterHome 'bin/jmeter.bat'
if (-not (Test-Path -LiteralPath $jmeter)) { throw '未找到 JMeter，请先运行 scripts/install-jmeter.ps1 或传入 -JMeterHome。' }
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME（Java 8+），或传入 -JavaHome。' }
$env:HEAP = '-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m'
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + "-$Scenario-$CacheMode"
$runDir = Join-Path $projectRoot "loadtest/results/$runName"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$jtlPath = Join-Path $runDir 'samples.jtl'
$metadata = [ordered]@{
    scenario = $Scenario; startedAt = (Get-Date).ToString('o'); host = '127.0.0.1'; port = $Port
    threads = $Threads; loops = $Loops; rampSeconds = $RampSeconds
    voucherId = $VoucherId; userBase = $UserBase; path = $Path; jmeter = '5.6.3'; serverCacheModeLabel = $CacheMode
    note = '本机闭环压测；登录单独统计。accepted 表示请求受理，不代表最终订单创建成功。'
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'run.json') -Encoding UTF8
& $jmeter -n -t (Join-Path $projectRoot "loadtest/$Scenario.jmx") -l $jtlPath -j (Join-Path $runDir 'jmeter.log') `
    "-Jhost=127.0.0.1" "-Jport=$Port" "-Jthreads=$Threads" "-Jloops=$Loops" "-Jramp=$RampSeconds" `
    "-JvoucherId=$VoucherId" "-JuserBase=$UserBase" "-Jpath=$Path" `
    '-Jsample_variables=businessOutcome,orderId,businessMessage' `
    '-Jjmeter.save.saveservice.response_data=false' '-Jjmeter.save.saveservice.samplerData=false' `
    '-Jjmeter.save.saveservice.requestHeaders=false' '-Jjmeter.save.saveservice.responseHeaders=false' `
    -e -o (Join-Path $runDir 'dashboard')
if ($LASTEXITCODE -ne 0) { throw "JMeter 运行失败，查看 $runDir/jmeter.log" }
& (Join-Path $PSScriptRoot 'summarize-loadtest.ps1') -JtlPath $jtlPath
Write-Output "压测文件目录：$runDir"
Write-Output '请继续查询最终订单、重复订单与剩余库存；不要把受理率写成订单成功率。'
