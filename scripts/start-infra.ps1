[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw '请先启动 Docker Desktop，并选择 Linux containers。' }
& docker compose -f (Join-Path $projectRoot 'infra/compose.yaml') up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw '基础设施启动失败，请检查 docker compose 日志。' }
& docker compose -f (Join-Path $projectRoot 'infra/compose.yaml') ps
