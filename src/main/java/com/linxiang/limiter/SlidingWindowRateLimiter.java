package com.linxiang.limiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

/** ZSet 的 score 是 Redis 服务端时间，member 是请求 UUID；同一毫秒的请求不会互相覆盖。 */
@Component
public class SlidingWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("lua/sliding-window-limit.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean allow(String key, int maxRequests, int windowSeconds) {
        if (maxRequests < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("限流窗口与阈值必须大于 0");
        }
        Long result = redis.execute(SCRIPT, Collections.singletonList(key),
                String.valueOf(windowSeconds * 1000L), String.valueOf(maxRequests), UUID.randomUUID().toString());
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("Redis 限流脚本返回异常");
        }
        return result == 1L;
    }
}
