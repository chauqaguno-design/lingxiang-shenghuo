package com.linxiang.service.cache;

import com.linxiang.entity.SeckillVoucher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;

/** 发布活动后的运行数据初始化。展示缓存刷新不能覆盖这份剩余名额。 */
@Component
public class VoucherActivityCache {
    private static final DefaultRedisScript<Long> INITIALIZE = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end " +
                    "redis.call('set', KEYS[1], ARGV[1]); " +
                    "redis.call('hset', KEYS[2], 'begin', ARGV[2], 'end', ARGV[3]); return 1", Long.class);
    private final StringRedisTemplate redis;
    private final ZoneId businessZone;

    public VoucherActivityCache(StringRedisTemplate redis,
                                @Value("${app.business-zone:Asia/Shanghai}") String businessZone) {
        this.redis = redis;
        this.businessZone = ZoneId.of(businessZone);
    }

    public void initializeNewActivity(SeckillVoucher activity) {
        long begin = activity.getBeginTime().atZone(businessZone).toInstant().toEpochMilli();
        long end = activity.getEndTime().atZone(businessZone).toInstant().toEpochMilli();
        // 已有库存时不覆盖，避免重试初始化把已抢走的名额恢复。新活动两项数据用 Lua 一次写入。
        // 不设置 TTL：已有预占请求可能稍后才消费，活动数据需要在订单结清后清理。
        Long initialized = redis.execute(INITIALIZE, Arrays.asList(
                        "seckill:stock:" + activity.getVoucherId(), "seckill:activity:" + activity.getVoucherId()),
                String.valueOf(activity.getStock()), String.valueOf(begin), String.valueOf(end));
        if (initialized == null) {
            throw new IllegalStateException("活动已保存，但 Redis 初始化失败，请检查服务后恢复活动");
        }
    }
}
