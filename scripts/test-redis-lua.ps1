[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = [Guid]::NewGuid().ToString('N')
$namespace = "test:linxiang:$runId"
$stockKey = "${namespace}:stock"
$activityKey = "${namespace}:activity"
$usersKey = "${namespace}:users"
$reserveFile = "/tmp/linxiang-reserve-$runId.lua"
$releaseFile = "/tmp/linxiang-release-$runId.lua"
$limitFile = "/tmp/linxiang-limit-$runId.lua"
$records = [Collections.Generic.List[object]]::new()
$createdKeys = [Collections.Generic.List[string]]::new()
$createdKeys.Add($stockKey); $createdKeys.Add($activityKey); $createdKeys.Add($usersKey)

function Invoke-TestRedis([string[]]$Arguments) {
    $result = & docker exec linxiang-redis redis-cli --raw @Arguments
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -match '^(ERR|WRONGTYPE)') { throw "Redis command failed: $result" }
    return ($result -join "`n").Trim()
}
function Assert-Value([string]$Name, [string]$Actual, [string]$Expected) {
    $passed = $Actual -eq $Expected
    $records.Add([ordered]@{ check = $Name; expected = $Expected; actual = $Actual; passed = $passed })
    if (-not $passed) { throw "$Name expected=$Expected actual=$Actual" }
}
function Invoke-Reserve([string]$User, [string]$Order) {
    $key = "${namespace}:reservation:$Order"
    if (-not $createdKeys.Contains($key)) { $createdKeys.Add($key) }
    return Invoke-TestRedis -Arguments @('--eval', $reserveFile, $stockKey, $activityKey, $usersKey, $key, ',', $User, $Order)
}
function Invoke-Release([string]$User, [string]$Order, [string]$KeepUser) {
    return Invoke-TestRedis -Arguments @('--eval', $releaseFile, $stockKey, $usersKey, "${namespace}:reservation:$Order", ',', $User, $Order, $KeepUser)
}

$outputDirectory = Join-Path $projectRoot 'loadtest/results'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$outputPath = Join-Path $outputDirectory ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-redis-lua.json')
$failure = $null
try {
    foreach ($file in @(
        @('seckill.lua', $reserveFile),
        @('release-reservation.lua', $releaseFile),
        @('lua/sliding-window-limit.lua', $limitFile)
    )) {
        & docker cp (Join-Path $projectRoot "src/main/resources/$($file[0])") "linxiang-redis:$($file[1])"
        if ($LASTEXITCODE -ne 0) { throw '复制项目实际 Lua 文件失败。' }
    }
    $clock = (Invoke-TestRedis -Arguments @('TIME')) -split "`n"
    $nowMs = [long]$clock[0] * 1000 + [long]([Math]::Floor([long]$clock[1] / 1000))
    Invoke-TestRedis -Arguments @('SET', $stockKey, '2') | Out-Null
    Invoke-TestRedis -Arguments @('HSET', $activityKey, 'begin', ($nowMs - 60000).ToString(), 'end', ($nowMs + 60000).ToString()) | Out-Null

    Assert-Value '首次预占成功' (Invoke-Reserve 'u1' 'o1') '0'
    Assert-Value '首次预占仅扣一份' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '1'
    Assert-Value '相同订单重试幂等成功' (Invoke-Reserve 'u1' 'o1') '0'
    Assert-Value '重试没有再次扣库存' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '1'
    Assert-Value '同用户另一订单被拒绝' (Invoke-Reserve 'u1' 'o2') '2'
    Assert-Value '第二位用户成功' (Invoke-Reserve 'u2' 'o3') '0'
    Assert-Value '库存恰好为零' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '0'
    Assert-Value '库存耗尽后拒绝第三位用户' (Invoke-Reserve 'u3' 'o4') '1'
    Assert-Value '库存没有变负数' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '0'

    Assert-Value '取消已建订单释放一次' (Invoke-Release 'u1' 'o1' '1') '1'
    Assert-Value '重复取消返回已处理' (Invoke-Release 'u1' 'o1' '1') '0'
    Assert-Value '重复取消合计仅增加一份' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '1'
    Assert-Value '取消后保留一人一券参与记录' (Invoke-TestRedis -Arguments @('HEXISTS', $usersKey, 'u1')) '1'
    Assert-Value '取消后不能再次抢同活动' (Invoke-Reserve 'u1' 'o2') '2'
    Assert-Value '其他用户可抢回补名额' (Invoke-Reserve 'u3' 'o4') '0'

    Assert-Value '建单前补偿成功' (Invoke-Release 'u2' 'o3' '0') '1'
    Assert-Value '建单前补偿移除参与记录' (Invoke-TestRedis -Arguments @('HEXISTS', $usersKey, 'u2')) '0'
    Assert-Value '建单前补偿后新订单可再抢' (Invoke-Reserve 'u2' 'o5') '0'
    Assert-Value '错误用户不能释放别人的预占' (Invoke-Release 'u3' 'o5' '0') '-1'
    Assert-Value '错误释放没有增加库存' (Invoke-TestRedis -Arguments @('GET', $stockKey)) '0'
    Assert-Value '已释放旧订单不能重新预占' (Invoke-Reserve 'u1' 'o1') '4'

    Invoke-TestRedis -Arguments @('HSET', $activityKey, 'end', ($nowMs - 1).ToString()) | Out-Null
    Assert-Value '已结束活动拒绝新请求' (Invoke-Reserve 'u4' 'o6') '7'
    Invoke-TestRedis -Arguments @('HSET', $activityKey, 'begin', ($nowMs + 60000).ToString(), 'end', ($nowMs + 120000).ToString()) | Out-Null
    Assert-Value '未开始活动拒绝新请求' (Invoke-Reserve 'u4' 'o6') '6'

    $limitKey = "${namespace}:limit"
    $createdKeys.Add($limitKey)
    Assert-Value '窗口内请求1通过' (Invoke-TestRedis -Arguments @('--eval', $limitFile, $limitKey, ',', '60000', '2', 'request-1')) '1'
    Assert-Value '窗口内请求2通过' (Invoke-TestRedis -Arguments @('--eval', $limitFile, $limitKey, ',', '60000', '2', 'request-2')) '1'
    Assert-Value '窗口内请求3限流' (Invoke-TestRedis -Arguments @('--eval', $limitFile, $limitKey, ',', '60000', '2', 'request-3')) '0'
    Assert-Value '独立请求标识保留两条记录' (Invoke-TestRedis -Arguments @('ZCARD', $limitKey)) '2'
} catch {
    $failure = $_.Exception.Message
} finally {
    # 所有 key 都由本次 GUID 生成，精确删除，不扫描、不触碰业务券/用户。
    Invoke-TestRedis -Arguments (@('DEL') + $createdKeys.ToArray()) | Out-Null
    & docker exec linxiang-redis rm -f $reserveFile $releaseFile $limitFile
    [ordered]@{
        testedAt = (Get-Date).ToString('o'); target = 'linxiang-redis'; redisVersion = '7.2.10'
        namespace = $namespace; checks = $records; failure = $failure; businessDataTouched = $false
        sourceFiles = @('src/main/resources/seckill.lua', 'src/main/resources/release-reservation.lua', 'src/main/resources/lua/sliding-window-limit.lua')
        sourceSha256 = @('seckill.lua', 'release-reservation.lua', 'lua/sliding-window-limit.lua') | ForEach-Object {
            [ordered]@{ file = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $projectRoot "src/main/resources/$_") -Algorithm SHA256).Hash }
        }
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputPath -Encoding UTF8
}
if ($failure) { throw "Lua验证失败：$failure；证据：$outputPath" }
Write-Output "$($records.Count) 项真实 Redis Lua 验证全部通过；证据：$outputPath"
