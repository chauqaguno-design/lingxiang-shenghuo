-- 释放标记与 INCR 在同一脚本中执行，重复补偿不会重复增加库存。
local state = redis.call('HGET', KEYS[3], 'state')
if state == 'RELEASED' then return 0 end
if state ~= 'RESERVED' or redis.call('HGET', KEYS[3], 'user') ~= ARGV[1] then return -1 end
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
redis.call('INCR', KEYS[1])
redis.call('HSET', KEYS[3], 'state', 'RELEASED')
-- 已经创建的订单即使取消，也保留本次活动的参与记录，明确一人一券规则。
if ARGV[3] == '0' and redis.call('HGET', KEYS[2], ARGV[1]) == ARGV[2] then
    redis.call('HDEL', KEYS[2], ARGV[1])
end
return 1
