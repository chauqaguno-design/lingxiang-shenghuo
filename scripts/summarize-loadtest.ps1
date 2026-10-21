[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$JtlPath)
$ErrorActionPreference = 'Stop'
$samples = @(Import-Csv -LiteralPath $JtlPath)
if ($samples.Count -eq 0) { throw 'JTL 中没有样本。' }
$summary = foreach ($group in ($samples | Group-Object label)) {
    $rows = @($group.Group)
    $times = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
    $start = ($rows | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum
    $end = ($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
    $seconds = [Math]::Max(($end - $start) / 1000.0, 0.001)
    $httpNon2xx = @($rows | Where-Object { $_.responseCode -notmatch '^2[0-9]{2}$' }).Count
    $http429 = @($rows | Where-Object responseCode -eq '429').Count
    $rawJMeterErrors = @($rows | Where-Object success -ne 'true').Count
    $systemErrors = @($rows | Where-Object { $_.success -ne 'true' -and $_.businessOutcome -ne 'rate_limited' }).Count
    $businessRejections = @($rows | Where-Object { $_.businessOutcome -in @('rejected', 'rate_limited') }).Count
    $outcomes = [ordered]@{}
    foreach ($outcome in ($rows | Group-Object businessOutcome)) { $outcomes[$outcome.Name] = $outcome.Count }
    $messages = [ordered]@{}
    foreach ($message in ($rows | Where-Object { $_.businessOutcome -in @('rejected', 'rate_limited') } | Group-Object businessMessage)) { $messages[$message.Name] = $message.Count }
    [ordered]@{
        label = $group.Name; requests = $rows.Count
        sampleWindowMs = $end - $start
        peakActiveJMeterThreads = ($rows | ForEach-Object { [int]$_.allThreads } | Measure-Object -Maximum).Maximum
        httpNon2xxCount = $httpNon2xx
        httpNon2xxRatePercent = [Math]::Round(100.0 * $httpNon2xx / $rows.Count, 2)
        http429Count = $http429
        http429RatePercent = [Math]::Round(100.0 * $http429 / $rows.Count, 2)
        businessRejectionRatePercent = [Math]::Round(100.0 * $businessRejections / $rows.Count, 2)
        rawJMeterFailureCount = $rawJMeterErrors
        rawJMeterFailureRatePercent = [Math]::Round(100.0 * $rawJMeterErrors / $rows.Count, 2)
        transportOrAssertionErrorsExcludingExpected429 = $systemErrors
        systemExceptionRatePercent = [Math]::Round(100.0 * $systemErrors / $rows.Count, 2)
        averageMs = [Math]::Round(($times | Measure-Object -Average).Average, 2)
        p95Ms = $times[[Math]::Max(0, [Math]::Ceiling($times.Count * 0.95) - 1)]
        p99Ms = $times[[Math]::Max(0, [Math]::Ceiling($times.Count * 0.99) - 1)]
        maxMs = $times[-1]; requestThroughputPerSecond = [Math]::Round($rows.Count / $seconds, 2)
        businessOutcomes = $outcomes; rejectionMessages = $messages
    }
}
$summaryPath = Join-Path (Split-Path -Parent $JtlPath) 'summary.json'
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Write-Output
