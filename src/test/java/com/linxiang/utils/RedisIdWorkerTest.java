package com.linxiang.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 验证 ID 的位布局、计数边界以及多个生成器实例并发调用时是否产生重复 ID。
 * 使用原子计数模拟共享 Redis INCR，不依赖真实 Redis，也不作为 Redis 性能测试。
 */
class RedisIdWorkerTest {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final long SEQUENCE_MASK = 0xFFFFFFFFL;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisIdWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        worker = new RedisIdWorker(redis);
    }

    @Test
    void idContainsUtcTimestampAndSequenceAndUsesBusinessDailyCounter() {
        when(values.increment(anyString())).thenReturn(7L);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        long id = worker.nextId("order");

        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        assertTrue(id > 0);
        assertEquals(7L, id & SEQUENCE_MASK, "低 32 位必须保留 Redis 返回的序列号");
        long timestamp = id >>> 32;
        assertTrue(timestamp >= before.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP);
        assertTrue(timestamp <= after.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).increment(key.capture());
        // 调用恰好跨越 UTC 午夜时，两天的 key 均合法，避免时间边界造成偶发失败。
        String beforeKey = "icr:order:" + before.format(DATE_FORMATTER);
        String afterKey = "icr:order:" + after.format(DATE_FORMATTER);
        assertTrue(key.getValue().equals(beforeKey) || key.getValue().equals(afterKey));
        verifyNoMoreInteractions(values);
    }

    @Test
    void unsigned32BitSequenceDoesNotCorruptTimestampOrSignBit() {
        // 32 位序列号可超过 Java int 的正数上限，因此不能用 int 保存或移位。
        when(values.increment(anyString())).thenReturn(SEQUENCE_MASK);
        long before = LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        long id = worker.nextId("order");

        long after = LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        assertTrue(id > 0);
        assertEquals(SEQUENCE_MASK, id & SEQUENCE_MASK);
        assertTrue((id >>> 32) >= before && (id >>> 32) <= after);
    }

    @Test
    void sequenceBeyond32BitsIsRejectedInsteadOfOverlappingTimestamp() {
        when(values.increment(anyString())).thenReturn(1L << 32);

        assertThrows(IllegalStateException.class, () -> worker.nextId("order"));
    }

    @Test
    void missingOrNonPositiveSequenceIsRejectedInsteadOfReturningAnInvalidId() {
        when(values.increment(anyString())).thenReturn(null, 0L, -1L);

        assertThrows(IllegalStateException.class, () -> worker.nextId("order"));
        assertThrows(IllegalStateException.class, () -> worker.nextId("order"));
        assertThrows(IllegalStateException.class, () -> worker.nextId("order"));
    }

    @Test
    void twoWorkersSharingAtomicCountersGenerateThirtyThousandDistinctIds() throws Exception {
        // 模拟两个应用实例共享同一套 Redis 计数器，各日期的计数互相独立。
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        when(values.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        });
        RedisIdWorker secondWorker = new RedisIdWorker(redis);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        List<Future<List<Long>>> futures = new ArrayList<>();

        try {
            // 与课程演示一样共生成 300 × 100 个 ID，但减少线程数并断言唯一性，避免刷屏输出。
            for (int task = 0; task < 300; task++) {
                RedisIdWorker currentWorker = task % 2 == 0 ? worker : secondWorker;
                futures.add(executor.submit(() -> {
                    List<Long> ids = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        ids.add(currentWorker.nextId("order"));
                    }
                    return ids;
                }));
            }

            Set<Long> uniqueIds = new HashSet<>();
            for (Future<List<Long>> future : futures) {
                for (Long id : future.get(30, TimeUnit.SECONDS)) {
                    assertTrue(id > 0);
                    assertTrue(uniqueIds.add(id), "出现重复 ID: " + id);
                }
            }
            assertEquals(30000, uniqueIds.size());
            long totalIncrements = counters.values().stream().mapToLong(AtomicLong::get).sum();
            assertEquals(30000L, totalIncrements);
        } finally {
            // 无论断言成功还是失败都释放测试线程，避免测试进程残留。
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
