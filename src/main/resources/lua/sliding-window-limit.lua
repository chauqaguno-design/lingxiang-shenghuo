-- 整个窗口的清理、计数、写入放在同一个脚本内，避免并发请求同时通过计数判断。
-- KEYS[1] = 用户 + 券的限流 key；ARGV = 窗口毫秒、最大次数、本次请求 UUID。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local window = tonumber(ARGV[1])
local maximum = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= maximum then
    return 0
end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
return 1
