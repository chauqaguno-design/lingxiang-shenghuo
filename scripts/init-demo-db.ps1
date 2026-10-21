[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$schemaPath = Join-Path $projectRoot 'src/main/resources/db/linxiang.sql'
if (-not (Test-Path -LiteralPath $schemaPath)) { throw "找不到初始化 SQL：$schemaPath" }
$tableCount = & docker exec linxiang-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();"'
if ($LASTEXITCODE -ne 0) { throw '无法连接本项目演示数据库，请先运行 start-infra.ps1。' }
if ([int]$tableCount -gt 0) { throw 'linxiang 数据库已有表，已停止初始化以保留现有订单。无需重复导入。' }
& docker cp $schemaPath 'linxiang-mysql:/tmp/linxiang-init.sql'
if ($LASTEXITCODE -ne 0) { throw '复制初始化 SQL 失败。' }
& docker exec linxiang-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" < /tmp/linxiang-init.sql'
if ($LASTEXITCODE -ne 0) { throw 'SQL 导入失败，请检查控制台错误，不要继续启动应用。' }
Write-Output 'linxiang 演示数据库已初始化。'
