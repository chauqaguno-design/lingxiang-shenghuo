package com.linxiang.service.impl;

import com.linxiang.dto.Result;
import com.linxiang.entity.Shop;
import com.linxiang.utils.CacheClient;
import com.linxiang.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopServiceImplTest {
    private ShopServiceImpl service;
    private CacheClient cacheClient;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        cacheClient = mock(CacheClient.class);
        redis = mock(StringRedisTemplate.class);
        service = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryByIdDelegatesToPassThroughCacheWithDatabaseFallback() {
        Shop shop = new Shop().setId(1L).setName("商户");
        doReturn(shop).when(service).getById(1L);
        when(cacheClient.queryWithPassThrough(eq(RedisConstants.CACHE_SHOP_KEY), eq(1L), eq(Shop.class),
                any(Function.class), eq(RedisConstants.CACHE_SHOP_TTL), eq(TimeUnit.MINUTES)))
                .thenAnswer(invocation -> {
                    Function<Long, Shop> fallback = invocation.getArgument(3);
                    return fallback.apply(1L);
                });

        Result result = service.queryById(1L);

        assertTrue(result.getSuccess());
        assertEquals(shop, result.getData());
        verify(service).getById(1L);
    }

    @Test
    void nonexistentShopReturnsFailureInsteadOfSuccessfulNullData() {
        Result result = service.queryById(404L);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在！", result.getErrorMsg());
        assertNull(result.getData());
    }

    @Test
    void updateWritesDatabaseBeforeInvalidatingShopCache() {
        Shop shop = new Shop().setId(1L).setName("修改后的商户");
        doReturn(true).when(service).updateById(shop);

        Result result = service.update(shop);

        assertTrue(result.getSuccess());
        InOrder inOrder = inOrder(service, redis);
        inOrder.verify(service).updateById(shop);
        inOrder.verify(redis).delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
    }

    @Test
    void updateDefersBothCacheInvalidationsUntilTransactionCommit() {
        Shop shop = new Shop().setId(1L).setName("提交后生效");
        doReturn(true).when(service).updateById(shop);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop);
            verifyNoInteractions(redis);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(redis).delete(RedisConstants.CACHE_SHOP_KEY + 1L);
            verify(redis).delete(ShopServiceImpl.HOT_SHOP_KEY + 1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void unheatedHotShopFallsBackAndCreatesLogicalCacheInsteadOfReportingNotFound() {
        service.configureHotShopIds("1,2");
        Shop shop = new Shop().setId(1L).setName("热点商户");
        when(cacheClient.queryWithMutex(eq(RedisConstants.CACHE_SHOP_KEY), eq(1L), eq(Shop.class),
                any(Function.class), eq(RedisConstants.CACHE_SHOP_TTL), eq(TimeUnit.MINUTES))).thenReturn(shop);

        Result result = service.queryById(1L);

        assertTrue(result.getSuccess());
        assertSame(shop, result.getData());
        verify(cacheClient).setWithLogicalExpire(ShopServiceImpl.HOT_SHOP_KEY + 1L, shop, 30L, TimeUnit.SECONDS);
    }
}
