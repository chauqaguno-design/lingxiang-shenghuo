package com.linxiang.utils;

import cn.hutool.json.JSONUtil;
import com.linxiang.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheClientTest {
    private static final Long SHOP_ID = 1L;
    private static final String CACHE_KEY = RedisConstants.CACHE_SHOP_KEY + SHOP_ID;
    private static final String LOCK_KEY = RedisConstants.LOCK_SHOP_KEY + SHOP_ID;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private Function<Long, Shop> database;
    private CacheClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        database = mock(Function.class);
        when(redis.opsForValue()).thenReturn(values);
        client = new CacheClient(redis);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        // 中断测试不能影响同一线程后续执行的测试。
        Thread.interrupted();
    }

    @Test
    void cacheHitReturnsShopWithoutDatabaseLookupOrTtlRefresh() {
        Shop shop = shop("缓存商户");
        when(values.get(CACHE_KEY)).thenReturn(JSONUtil.toJsonStr(shop));

        assertEquals(shop, queryWithPassThrough());

        verifyNoInteractions(database);
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cacheMissStoresShopWithTtlAndTheNextRequestUsesCache() {
        Shop shop = shop("数据库商户");
        AtomicReference<String> cached = new AtomicReference<>();
        when(values.get(CACHE_KEY)).thenAnswer(invocation -> cached.get());
        when(database.apply(SHOP_ID)).thenReturn(shop);
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(eq(CACHE_KEY), anyString(), eq(RedisConstants.CACHE_SHOP_TTL), eq(TimeUnit.MINUTES));

        assertEquals(shop, queryWithPassThrough());
        assertEquals(shop, queryWithPassThrough());

        assertEquals(shop, JSONUtil.toBean(cached.get(), Shop.class));
        verify(database, times(1)).apply(SHOP_ID);
        verify(values, times(1)).set(eq(CACHE_KEY), anyString(), eq(RedisConstants.CACHE_SHOP_TTL), eq(TimeUnit.MINUTES));
    }

    @Test
    void nonexistentShopIsCachedAsEmptyStringWithShortTtl() {
        when(values.get(CACHE_KEY)).thenReturn(null, "");
        when(database.apply(SHOP_ID)).thenReturn(null);

        assertNull(queryWithPassThrough());
        assertNull(queryWithPassThrough());

        verify(database, times(1)).apply(SHOP_ID);
        verify(values).set(CACHE_KEY, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    @Test
    void logicalExpiryWrapsDataAndKeepsTheRedisKeyWithoutPhysicalTtl() {
        Shop shop = shop("逻辑过期商户");
        LocalDateTime before = LocalDateTime.now();

        client.setWithLogicalExpire(CACHE_KEY, shop, 2L, TimeUnit.MINUTES);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(CACHE_KEY), json.capture());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        RedisData data = JSONUtil.toBean(json.getValue(), RedisData.class);
        assertEquals(shop, JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), Shop.class));
        assertTrue(data.getExpireTime().isAfter(before.plusSeconds(119)));
        assertFalse(data.getExpireTime().isAfter(LocalDateTime.now().plusMinutes(2)));

        when(values.get(CACHE_KEY)).thenReturn(json.getValue());
        assertEquals(shop, queryWithLogicalExpire());
        verifyNoInteractions(database);
    }

    @Test
    void mutexWaiterUsesCachePopulatedByAnotherRequestWithoutReleasingItsLock() {
        Shop shop = shop("其他请求回填的商户");
        when(values.get(CACHE_KEY)).thenReturn(null, JSONUtil.toJsonStr(shop));
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertEquals(shop, queryWithMutex());

        verifyNoInteractions(database);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(redis, never()).delete(LOCK_KEY);
    }

    @Test
    void mutexOwnerChecksCacheAgainAndReleasesOnlyItsOwnLockToken() {
        Shop shop = shop("获取锁前刚回填的商户");
        when(values.get(CACHE_KEY)).thenReturn(null, JSONUtil.toJsonStr(shop));
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        assertEquals(shop, queryWithMutex());

        verifyNoInteractions(database);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq(LOCK_KEY), token.capture(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS));
        assertNotNull(token.getValue());
        assertFalse(token.getValue().isEmpty());
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), eq(token.getValue()));
        verify(redis, never()).delete(LOCK_KEY);
    }

    @Test
    void interruptedMutexWaitPreservesInterruptStatusAndDoesNotReleaseAnotherOwnersLock() {
        when(values.get(CACHE_KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, this::queryWithMutex);
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(database);
            verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());
            verify(redis, never()).delete(LOCK_KEY);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expiredLogicalCacheReturnsOldShopWhileBackgroundTaskRebuildsIt() throws InterruptedException {
        Shop oldShop = shop("旧商户");
        Shop newShop = shop("更新后的商户");
        when(values.get(CACHE_KEY)).thenReturn(logicalJson(oldShop, LocalDateTime.now().minusMinutes(1)));
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        CountDownLatch databaseStarted = new CountDownLatch(1);
        CountDownLatch allowDatabaseReturn = new CountDownLatch(1);
        when(database.apply(SHOP_ID)).thenAnswer(invocation -> {
            databaseStarted.countDown();
            assertTrue(allowDatabaseReturn.await(5, TimeUnit.SECONDS), "测试未释放后台数据库查询");
            return newShop;
        });

        try {
            assertEquals(oldShop, queryWithLogicalExpire());
            assertTrue(databaseStarted.await(2, TimeUnit.SECONDS));
            verify(values, never()).set(eq(CACHE_KEY), anyString());
        } finally {
            allowDatabaseReturn.countDown();
        }

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values, timeout(2000)).set(eq(CACHE_KEY), json.capture());
        RedisData rebuilt = JSONUtil.toBean(json.getValue(), RedisData.class);
        assertEquals(newShop, JSONUtil.toBean(JSONUtil.toJsonStr(rebuilt.getData()), Shop.class));
        assertTrue(rebuilt.getExpireTime().isAfter(LocalDateTime.now()));
        verify(redis, timeout(2000)).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), anyString());
    }

    @Test
    void logicalRebuildOfDeletedShopStoresAnEmptyValueWithShortTtl() {
        Shop oldShop = shop("已删除商户的旧缓存");
        when(values.get(CACHE_KEY)).thenReturn(logicalJson(oldShop, LocalDateTime.now().minusMinutes(1)));
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(RedisConstants.LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(database.apply(SHOP_ID)).thenReturn(null);

        assertEquals(oldShop, queryWithLogicalExpire());

        verify(values, timeout(2000)).set(CACHE_KEY, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        verify(redis, timeout(2000)).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), anyString());
        verify(values, never()).set(eq(CACHE_KEY), anyString());
    }

    private Shop queryWithPassThrough() {
        return client.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, SHOP_ID, Shop.class,
                database, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    private Shop queryWithMutex() {
        return client.queryWithMutex(RedisConstants.CACHE_SHOP_KEY, SHOP_ID, Shop.class,
                database, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    private Shop queryWithLogicalExpire() {
        return client.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, SHOP_ID, Shop.class,
                database, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    private Shop shop(String name) {
        return new Shop().setId(SHOP_ID).setName(name).setTypeId(1L);
    }

    private String logicalJson(Shop shop, LocalDateTime expiry) {
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(expiry);
        return JSONUtil.toJsonStr(data);
    }
}
