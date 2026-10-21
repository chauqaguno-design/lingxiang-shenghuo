-- 三个 key：秒杀库存、已抢券的用户、已回补的订单。
local stockKey = KEYS[1]
local userKey = KEYS[2]
local returnedKey = KEYS[3]
local userId = ARGV[1]
local orderId = ARGV[2]

-- 1.按订单 ID 去重。旧任务重试时，不能再次加库存或删除用户新订单的资格。
if redis.call('SISMEMBER', returnedKey, orderId) == 1 then
    return 0
end

-- 2.库存和用户占用必须存在，数据缺失时留给定时任务重试。
if not tonumber(redis.call('GET', stockKey)) then
    return -1
end
if redis.call('SISMEMBER', userKey, userId) == 0 then
    return -2
end

-- 3.回补一张券，允许这个用户重新抢券，记录本订单已经回补。
redis.call('INCR', stockKey)
redis.call('SREM', userKey, userId)
redis.call('SADD', returnedKey, orderId)
-- 去重记录不设置过期，避免旧订单重试时重复回补。
return 1
