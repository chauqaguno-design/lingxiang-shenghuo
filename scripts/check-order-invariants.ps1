[CmdletBinding()]
param(
    [ValidateRange(1, [long]::MaxValue)][long]$VoucherId = 101,
    [ValidateRange(0, [int]::MaxValue)][int]$InitialStock = 20,
    [string]$RedisStockPrefix = 'seckill:stock:'
)
$ErrorActionPreference = 'Stop'
# 密码通过容器已有环境变量读取，不读取本机 MySQL 配置。
# 此脚本只执行 SELECT/GET，不会清空订单或重置库存。
$sql = @"
SELECT 'voucher_stock' AS section, voucher_id, stock FROM tb_seckill_voucher WHERE voucher_id=$VoucherId;
SELECT 'orders_by_status' AS section, status, COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id=$VoucherId GROUP BY status ORDER BY status;
SELECT 'duplicate_user_voucher' AS section, user_id, voucher_id, COUNT(*) AS duplicate_count FROM tb_voucher_order WHERE voucher_id=$VoucherId GROUP BY user_id,voucher_id HAVING COUNT(*)>1;
SELECT 'negative_stock' AS section, voucher_id,stock FROM tb_seckill_voucher WHERE voucher_id=$VoucherId AND stock<0;
SELECT 'without_cancel_only' AS section, $InitialStock AS initial_stock, s.stock AS remaining_stock, (SELECT COUNT(*) FROM tb_voucher_order o WHERE o.voucher_id=$VoucherId) AS all_order_count FROM tb_seckill_voucher s WHERE s.voucher_id=$VoucherId;
"@
$sql | & docker exec -i linxiang-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) { throw '数据库验收查询失败。' }
Write-Output 'Redis remaining stock:'
& docker exec linxiang-redis redis-cli GET "$RedisStockPrefix$VoucherId"
if ($LASTEXITCODE -ne 0) { throw 'Redis 验收查询失败。' }
Write-Output '无取消/退款/补货时，initial_stock 应等于 remaining_stock + all_order_count。有关单时请按业务终态核对库存。'
