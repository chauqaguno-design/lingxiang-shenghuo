package com.linxiang.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 简易 Redis 分布式锁：多个应用实例通过同一个 key 竞争同一份资源。
 * 加锁使用 SET NX + 过期时间；解锁使用 Lua 一次完成“比较所有者 + 删除”。
 * 不支持重入和自动续期，业务超过持锁时间仍会失去锁，不能替代完整的 Redisson 锁。
 */
public class SimpleRedisLock implements ILock {

    /** 所有锁统一加此前缀，例如业务名称 order:10 对应 Redis key lock:order:10。 */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 类加载时生成一次 UUID，同一个 JVM 中的线程共享这个前缀。
     * 仅使用线程 ID 不够：两个 JVM 都可能存在 ID 为 20 的线程。
     * UUID 前缀加线程 ID，才能区分不同应用实例中的线程。
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString().replace("-", "") + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static {
        // 类加载时准备解锁脚本，后续释放锁都复用它。
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;


    /**
     * @param name 业务锁名称；例如 order:用户ID，让同一用户的下单请求竞争同一把锁
     * @param stringRedisTemplate Spring 管理的 Redis 客户端；由业务层传入即可
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 1. 生成当前线程的所有者标识，用于解锁时核对身份。
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 2. 不存在才写入，并同时设置 TTL；拆成两条命令可能留下没有过期时间的锁。
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        // 3. 只有明确返回 true 才算成功；失败立即返回，由调用方决定是否稍后重试。
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        // Lua 内先比较所有者，再删除锁，两步原子执行；锁过期后被别人持有时不会误删。
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());
    }
    }
