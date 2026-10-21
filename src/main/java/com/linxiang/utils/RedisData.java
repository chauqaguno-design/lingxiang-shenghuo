package com.linxiang.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存的包装对象：在原有业务数据外面再保存一个“数据何时需要刷新”的时间点。
 * 例如商户缓存不再只保存 Shop 的 JSON，而是保存包含 expireTime、data 两个字段的 JSON。
 *
 * <p>expireTime 由应用代码判断，Redis 不会因为这个字段到期而自动删除 key。
 * 这样后台重建期间仍然能读取 data 中的旧数据。普通 TTL 缓存则不使用本包装对象。</p>
 */
@Data
public class RedisData {
    // 逻辑过期时间：当前时间达到此值时需要刷新数据，和 Redis key 的 TTL 是两个概念。
    private LocalDateTime expireTime;
    // 实际业务数据，例如 Shop。声明为 Object 便于复用，反序列化时还需要指定具体业务类型。
    private Object data;
}
