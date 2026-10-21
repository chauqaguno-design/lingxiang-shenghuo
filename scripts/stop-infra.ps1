[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# 不加 -v，保留本项目数据库和 Redis/MQ 数据；不操作其他 compose 项目。
& docker compose -f (Join-Path $projectRoot 'infra/compose.yaml') down
if ($LASTEXITCODE -ne 0) { throw '停止基础设施失败。' }
