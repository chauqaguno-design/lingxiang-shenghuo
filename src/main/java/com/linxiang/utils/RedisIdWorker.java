package com.linxiang.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 的订单 ID 生成器。
 * 优惠券本身的 id 仍使用数据库自增，不需要改成调用本工具。
 *
 * <p>一个正数 long 共使用 63 位：高 31 位保存距离起始时间的秒数，低 32 位保存自增序列号，
 * 最高的符号位保持为 0。即使同一秒产生多个 ID，也可以用不同的序列号区分。</p>
 *
 * <p>同一业务的多个应用实例必须使用相同的 keyPrefix，并共享 Redis 计数器。
 * 不同业务前缀使用独立计数器，前缀本身没有编码进 ID，因此不同前缀之间不保证数值互不重复。
 * Redis 计数器不可随意删除、重置或恢复到旧值；本实现也不处理系统时钟回拨。</p>
 */
@Component
public class RedisIdWorker {

    // 起始时间：2022-01-01 00:00:00 UTC 对应的秒级时间戳。
    // 采用相对时间缩小数值；已经开始使用后，不应随意修改这个基准。
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 低 32 位预留给序列号，其最大值为 2^32 - 1。
    private static final int COUNT_BITS = 32;
    private static final long MAX_COUNT = (1L << COUNT_BITS) - 1;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private final StringRedisTemplate stringRedisTemplate;

    // 构造器注入：Spring 自动传入已配置好的 Redis 客户端。
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成指定业务的下一个 ID，例如 nextId("order") 生成订单 ID。
     *
     * @param keyPrefix 业务前缀；同一张订单表的所有生成入口应使用相同的前缀
     * @return 由相对时间戳和 Redis 序列号组成的正数 long
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间部分。统一使用 UTC 读取时间和划分日期，避免依赖服务器的默认时区。
        // 日期与时间戳都从同一个 now 计算，避免跨午夜时分别取时间导致两部分不对应。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        if (timestamp < 0 || timestamp > Integer.MAX_VALUE) {
            throw new IllegalStateException("当前时间超出 ID 生成器支持的时间范围");
        }

        // 2. 生成序列号。按“业务 + 日期”建立计数器，例如 icr:order:2026:10:18。
        // 新的一天自然换一个 key，从 1 开始；本实现保留旧 key，不设置过期时间。
        String date = now.format(DATE_FORMATTER);
        // INCR 是 Redis 的原子操作：key 不存在时从 0 加到 1，多个请求会得到不同的递增值。
        // 原子递增由 Redis 完成，不需要依靠当前 Java 进程里的 synchronized。
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        if (count == null || count <= 0 || count > MAX_COUNT) {
            // 序列号越界时必须报错，否则超过 32 位的部分会侵入时间位，破坏 ID 组成规则。
            throw new IllegalStateException("Redis 序列号不可用或已超出 32 位范围");
        }

        // 3. 将时间部分左移 32 位，空出低 32 位，再通过按位或填入序列号。
        // 这里的 | 是二进制按位或，不是字符串拼接；两部分占用的位不重叠。
        return (timestamp << COUNT_BITS) | count;
    }
}
