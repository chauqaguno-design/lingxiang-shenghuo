package com.linxiang.service.cache;

import com.linxiang.config.VoucherCacheProperties;
import com.linxiang.entity.Voucher;
import com.linxiang.mapper.VoucherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherDisplayCacheTest {
    private VoucherMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private VoucherCacheProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(VoucherMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        properties = new VoucherCacheProperties();
    }

    @Test
    void mysqlModeSkipsBothCachesAndDoesNotExposeOrMutateDatabaseStock() {
        properties.setMode("mysql");
        Voucher row = new Voucher().setId(1L).setTitle("周末券").setStock(17);
        when(mapper.queryVoucherOfShop(10L)).thenReturn(Collections.singletonList(row));
        VoucherDisplayCache cache = new VoucherDisplayCache(mapper, redis, properties);

        List<Voucher> first = cache.queryByShop(10L);
        cache.queryByShop(10L);

        assertNull(first.get(0).getStock());
        assertEquals(17, row.getStock());
        verify(mapper, times(2)).queryVoucherOfShop(10L);
        verifyNoInteractions(redis);
    }

    @Test
    void caffeineHitSkipsRedisAndReturnsIndependentObjects() {
        LocalDateTime begin = LocalDateTime.of(2026, 10, 21, 12, 0);
        when(mapper.queryVoucherOfShop(10L)).thenReturn(Collections.singletonList(
                new Voucher().setId(1L).setTitle("原始标题").setStock(20).setBeginTime(begin)));
        VoucherDisplayCache cache = new VoucherDisplayCache(mapper, redis, properties);

        List<Voucher> first = cache.queryByShop(10L);
        first.get(0).setTitle("被调用者修改");
        List<Voucher> second = cache.queryByShop(10L);

        assertEquals("原始标题", second.get(0).getTitle());
        assertEquals(begin, second.get(0).getBeginTime());
        assertNull(second.get(0).getStock());
        verify(mapper).queryVoucherOfShop(10L);
        verify(values).get(VoucherDisplayCache.KEY_PREFIX + 10L);
        verify(values).set(eq(VoucherDisplayCache.KEY_PREFIX + 10L), anyString(), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void redisModeReadsRedisOnEveryRequestAndDoesNotLeakOldCachedStock() {
        properties.setMode("redis");
        when(values.get(VoucherDisplayCache.KEY_PREFIX + 10L))
                .thenReturn("[{\"id\":1,\"title\":\"优惠券\",\"stock\":99}]");
        VoucherDisplayCache cache = new VoucherDisplayCache(mapper, redis, properties);

        assertNull(cache.queryByShop(10L).get(0).getStock());
        cache.queryByShop(10L);

        verify(values, times(2)).get(VoucherDisplayCache.KEY_PREFIX + 10L);
        verifyNoInteractions(mapper);
    }

    @Test
    void emptyListUsesShortRedisTtlAndIsCachedLocally() {
        when(mapper.queryVoucherOfShop(404L)).thenReturn(Collections.emptyList());
        VoucherDisplayCache cache = new VoucherDisplayCache(mapper, redis, properties);

        assertTrue(cache.queryByShop(404L).isEmpty());
        assertTrue(cache.queryByShop(404L).isEmpty());

        verify(values).set(VoucherDisplayCache.KEY_PREFIX + 404L, "[]", 5L, TimeUnit.SECONDS);
        verify(mapper).queryVoucherOfShop(404L);
    }

    @Test
    void evictionClearsLocalAndRedisSoNextReadReloads() {
        when(mapper.queryVoucherOfShop(10L)).thenReturn(Collections.singletonList(new Voucher().setId(1L)));
        VoucherDisplayCache cache = new VoucherDisplayCache(mapper, redis, properties);
        cache.queryByShop(10L);

        cache.evictShop(10L);
        cache.queryByShop(10L);

        verify(redis).delete(VoucherDisplayCache.KEY_PREFIX + 10L);
        verify(mapper, times(2)).queryVoucherOfShop(10L);
    }

    @Test
    void rejectsInvalidModeInsteadOfSilentlyChangingBenchmarkBehavior() {
        properties.setMode("typo");
        assertThrows(IllegalArgumentException.class, () -> new VoucherDisplayCache(mapper, redis, properties));
    }
}
