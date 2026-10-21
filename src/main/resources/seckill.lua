-- KEYS: 库存、活动时间、用户参与记录、订单预占记录；ARGV: 用户、订单。
-- 重试先检查自己的预占，避免 Redis 已成功而网络超时导致再次扣减。
local state = redis.call('HGET', KEYS[4], 'state')
if state == 'RESERVED' and redis.call('HGET', KEYS[4], 'user') == ARGV[1] then return 0 end
if state == 'RELEASED' then return 4 end
local stock = redis.call('GET', KEYS[1])
local beginTime = redis.call('HGET', KEYS[2], 'begin')
local endTime = redis.call('HGET', KEYS[2], 'end')
if not stock or not beginTime or not endTime then return 5 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < tonumber(beginTime) then return 6 end
if now >= tonumber(endTime) then return 7 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return 2 end
if tonumber(stock) <= 0 then return 1 end
redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[4], 'state', 'RESERVED', 'user', ARGV[1])
return 0
