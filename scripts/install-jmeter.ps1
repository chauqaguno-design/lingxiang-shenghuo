[CmdletBinding()]
param([string]$ToolDirectory = (Join-Path $env:LOCALAPPDATA 'LinxiangTools'))
$ErrorActionPreference = 'Stop'
$version = '5.6.3'
$archiveName = "apache-jmeter-$version.zip"
$archivePath = Join-Path $ToolDirectory $archiveName
$checksumPath = "$archivePath.sha512"
$installPath = Join-Path $ToolDirectory "apache-jmeter-$version"
New-Item -ItemType Directory -Force -Path $ToolDirectory | Out-Null
if (-not (Test-Path -LiteralPath $archivePath)) {
    Invoke-WebRequest -Uri "https://dlcdn.apache.org/jmeter/binaries/$archiveName" -OutFile $archivePath
}
Invoke-WebRequest -Uri "https://downloads.apache.org/jmeter/binaries/$archiveName.sha512" -OutFile $checksumPath
$expected = ((Get-Content -LiteralPath $checksumPath -Raw) -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
if ($expected -notmatch '^[0-9a-fA-F]{128}$' -or $actual -ine $expected) {
    throw 'JMeter SHA512 校验失败；未解压执行，请检查下载文件。'
}
if (-not (Test-Path -LiteralPath (Join-Path $installPath 'bin/jmeter.bat'))) {
    Expand-Archive -LiteralPath $archivePath -DestinationPath $ToolDirectory
}
Write-Output "SHA512 OK: $actual"
Write-Output "JMeter: $installPath"
