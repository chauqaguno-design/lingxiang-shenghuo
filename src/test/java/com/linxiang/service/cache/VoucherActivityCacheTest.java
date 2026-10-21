package com.linxiang.service.cache;

import com.linxiang.entity.SeckillVoucher;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherActivityCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void initializationUsesBusinessTimezoneAndSeparateStockActivityKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        VoucherActivityCache cache = new VoucherActivityCache(redis, "Asia/Shanghai");
        SeckillVoucher activity = new SeckillVoucher();
        activity.setVoucherId(91L);
        activity.setStock(100);
        activity.setBeginTime(LocalDateTime.of(2026, 10, 21, 12, 0));
        activity.setEndTime(activity.getBeginTime().plusHours(2));

        cache.initializeNewActivity(activity);

        verify(redis).execute(any(RedisScript.class),
                eq(Arrays.asList("seckill:stock:91", "seckill:activity:91")), eq("100"),
                eq(String.valueOf(activity.getBeginTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())),
                eq(String.valueOf(activity.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())));
    }

    @Test
    void missingScriptResultDoesNotReportInitializationSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SeckillVoucher activity = new SeckillVoucher();
        activity.setVoucherId(1L);
        activity.setStock(10);
        activity.setBeginTime(LocalDateTime.of(2026, 10, 21, 12, 0));
        activity.setEndTime(activity.getBeginTime().plusHours(1));
        assertThrows(IllegalStateException.class,
                () -> new VoucherActivityCache(redis, "Asia/Shanghai").initializeNewActivity(activity));
    }
}
