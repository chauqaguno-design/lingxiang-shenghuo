package com.linxiang.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证锁调用协议：SET NX 带 TTL，释放只执行一次带所有者 token 的 Lua。
 * AtomicReference 模拟 Redis 脚本的原子比较删除；这不是实际 Redis 集成测试。
 */
class SimpleRedisLockTest {
    private static final String LOCK_KEY = "lock:order:101";
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleRedisLock lock;
    private AtomicReference<String> storedOwner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        lock = new SimpleRedisLock("order:101", redis);
        storedOwner = new AtomicReference<>();
        // 仅模拟原子脚本的返回语义，后续验证客户端没有退回 GET + DEL 两次调用。
        when(redis.execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), anyString()))
                .thenAnswer(invocation -> {
                    synchronized (storedOwner) {
                        String token = invocation.getArgument(2);
                        if (token.equals(storedOwner.get())) {
                            storedOwner.set(null);
                            return 1L;
                        }
                        return 0L;
                    }
                });
    }

    @Test
    void acquireStoresJvmAndThreadIdentityWithTimeoutInOneCommand() {
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(20L), eq(TimeUnit.SECONDS))).thenReturn(true);

        assertTrue(lock.tryLock(20L));

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq(LOCK_KEY), owner.capture(), eq(20L), eq(TimeUnit.SECONDS));
        assertTrue(owner.getValue().matches("[0-9a-f]{32}-" + Thread.currentThread().getId()));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void failedAcquireReturnsImmediatelyWithoutDeletingExistingLock() {
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(20L), eq(TimeUnit.SECONDS))).thenReturn(false);

        assertFalse(lock.tryLock(20L));

        verify(values).setIfAbsent(eq(LOCK_KEY), anyString(), eq(20L), eq(TimeUnit.SECONDS));
        verify(redis, never()).delete(anyString());
        verify(values, never()).get(anyString());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void nullAcquireReplyIsTreatedAsFailure() {
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(20L), eq(TimeUnit.SECONDS))).thenReturn(null);
        assertFalse(lock.tryLock(20L));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void ownerReleasesItsLockThroughOneAtomicScriptWithSameToken() {
        String owner = acquireAndCaptureOwner();

        lock.unlock();

        assertNull(storedOwner.get());
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), eq(owner));
        verify(values, never()).get(anyString());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void differentOwnerOrExpiredLockIsNotDeleted() {
        storedOwner.set("another-jvm-999");
        lock.unlock();
        assertEquals("another-jvm-999", storedOwner.get());

        storedOwner.set(null);
        lock.unlock();
        assertNull(storedOwner.get());

        verify(redis, times(2)).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), anyString());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void anotherThreadCannotReleaseOwnersLockEvenWithSameLockObject() throws InterruptedException {
        String owner = acquireAndCaptureOwner();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                lock.unlock();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        other.start();
        other.join(2000L);

        assertFalse(other.isAlive());
        assertNull(failure.get());
        assertEquals(owner, storedOwner.get());
        ArgumentCaptor<String> attemptedOwner = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), attemptedOwner.capture());
        assertNotEquals(owner, attemptedOwner.getValue());
        assertTrue(attemptedOwner.getValue().endsWith("-" + other.getId()));
    }

    @Test
    void expiredOwnerCannotDeleteReplacementLockAndDoesNotPerformSeparateGetDel() {
        String originalOwner = acquireAndCaptureOwner();
        storedOwner.set("replacement-owner-B");

        lock.unlock();

        assertEquals("replacement-owner-B", storedOwner.get(), "旧所有者的 token 不能释放新的锁");
        verify(redis).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), eq(originalOwner));
        verify(values, never()).get(anyString());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void repeatUnlockCannotDeleteLockAcquiredByAnotherOwnerBetweenCalls() {
        acquireAndCaptureOwner();
        lock.unlock();
        assertNull(storedOwner.get());
        storedOwner.set("later-owner-C");

        lock.unlock();

        assertEquals("later-owner-C", storedOwner.get());
        verify(redis, times(2)).execute(any(RedisScript.class), eq(Collections.singletonList(LOCK_KEY)), anyString());
    }

    private String acquireAndCaptureOwner() {
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), eq(20L), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> storedOwner.compareAndSet(null, invocation.getArgument(1)));
        assertTrue(lock.tryLock(20L));
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq(LOCK_KEY), owner.capture(), eq(20L), eq(TimeUnit.SECONDS));
        return owner.getValue();
    }
}
