package com.lingxiangshenghuo.utils;

import cn.hutool.json.JSONUtil;
import com.lingxiangshenghuo.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheClientBloomTest {

    private ValueOperations<String, String> values;
    private Function<Long, Shop> database;
    private Predicate<Long> bloom;
    private CacheClient cacheClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        database = mock(Function.class);
        bloom = mock(Predicate.class);
        when(redis.opsForValue()).thenReturn(values);
        cacheClient = new CacheClient(redis);
    }

    @Test
    void cachedShopDoesNotCheckBloomOrDatabase() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("测试商户");
        when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop));

        Shop result = query(1L);

        assertNotNull(result);
        assertEquals(shop.getId(), result.getId());
        assertEquals(shop.getName(), result.getName());
        verifyNoInteractions(bloom, database);
    }

    @Test
    void bloomRejectionDoesNotQueryDatabaseOrWriteEmptyCache() {
        when(bloom.test(999L)).thenReturn(false);

        assertNull(query(999L));

        verify(bloom).test(999L);
        verifyNoInteractions(database);
        verify(values).get("cache:shop:999");
        verifyNoMoreInteractions(values);
    }

    @Test
    void falsePositiveIsCachedForTwoMinutesAndNextRequestDoesNotQueryAgain() {
        // 第一次缓存未命中，第二次读到上次写入的空值。
        when(values.get("cache:shop:999")).thenReturn(null, "");
        when(bloom.test(999L)).thenReturn(true);
        when(database.apply(999L)).thenReturn(null);

        assertNull(query(999L));
        assertNull(query(999L));

        verify(database, times(1)).apply(999L);
        verify(bloom, times(1)).test(999L);
        verify(values).set("cache:shop:999", "", 2L, TimeUnit.MINUTES);
    }

    private Shop query(Long id) {
        return cacheClient.queryWithPassThrough(
                "cache:shop:", id, Shop.class, database, 30L, TimeUnit.MINUTES, bloom);
    }
}
