package com.linxiang.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.linxiang.utils.RedisConstants.*;

/**
 * 商户查询使用的 Redis 缓存工具，把“查缓存、查数据库、回写缓存”从业务 Service 中抽出来。
 * Service 只需要告诉本工具：缓存 key 怎么拼、结果是什么类型、缓存未命中时怎样查询数据库。
 *
 * <p>本类提供三种可选的查询策略：</p>
 * <ul>
 *     <li>空值缓存：数据库也不存在时缓存空字符串，避免同一个不存在的 id 反复访问数据库。</li>
 *     <li>互斥锁：热点缓存失效时，持锁请求负责查询数据库，其他请求等待缓存重建。</li>
 *     <li>逻辑过期：热点数据提前预热，过期后先返回旧值，再由后台任务查询数据库、更新缓存。</li>
 * </ul>
 *
 * <p>前两种策略直接保存业务对象的 JSON，并设置 Redis TTL；逻辑过期保存 RedisData 包装后的
 * JSON，正常数据不设置 Redis TTL。这两种存储格式不同，切换策略时必须重新准备对应的缓存。</p>
 */
@Slf4j
@Component
public class CacheClient {

    // 读写 Redis 的字符串：Java 对象需要先转成 JSON，读取后再还原成对应的 Java 对象。
    private final StringRedisTemplate stringRedisTemplate;

    // 逻辑过期时使用的后台执行器，最多同时执行 10 个重建任务。
    // 请求线程提交任务后即可返回旧数据；同一商户的重建竞争仍通过 Redis 锁协调。
    private final ExecutorService cacheRebuildExecutor = Executors.newFixedThreadPool(10);

    // KEYS[1] 是锁的 key，ARGV[1] 是获取锁时保存的唯一 token。
    // 只有 Redis 中的 token 仍属于当前请求，才删除锁；否则说明锁已过期或已被其他请求获取。
    // 必须用 Lua 把“比较 token”和“删除锁”作为一个原子操作执行，不能在 Java 中分两次调用。
    // 否则比较完成后锁恰好过期，其他请求获得新锁，当前请求的 DEL 就可能误删别人的锁。
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    // 构造器注入：Spring 创建 CacheClient 时，将容器中的 StringRedisTemplate 传入。
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入普通缓存，并为 Redis key 设置实际有效期。
     * 例如 time=30、unit=MINUTES，表示缓存有效期是 30 分钟，过期后 GET 不再返回该值。
     *
     * @param key 完整缓存 key，例如 cache:shop:1
     * @param value 需要缓存的业务对象，例如 Shop
     * @param time 有效期数值，必须和 unit 一起理解
     * @param unit 有效期单位，调用者可以选择分钟、秒等
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入逻辑过期缓存，用于热点数据的预热和后台重建。
     * 保存的内容包含两个部分：data 是业务数据，expireTime 是应用判断数据是否过期的时间点。
     *
     * <p>逻辑过期不会让 Redis 自动删除数据。即使 expireTime 已经过期，仍能读出旧值，
     * 从而在后台重建期间继续响应请求；因此该策略允许在一段时间内返回旧数据。</p>
     *
     * @param key 完整缓存 key，例如 cache:shop:1
     * @param value 要预热或刷新的业务对象
     * @param time 从现在开始，业务数据保持新鲜的时长
     * @param unit 时长单位；本实现转换为秒后计算逻辑过期时间
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 1. 用 RedisData 包装业务数据，不能直接使用普通缓存的 JSON 格式。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 2. 将“有效多长时间”转换为具体的“到哪个时间点过期”。
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 3. 特意使用不带 TTL 的 set：旧数据必须保留下来，才能在重建期间被返回。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 使用空值缓存解决缓存穿透：避免反复查询数据库中不存在的同一个 id。
     * 查询顺序：有效 JSON 直接返回；命中空字符串直接返回 null；key 不存在才查询数据库。
     *
     * <p>这项策略不能单独解决热点 key 失效时大量请求同时回源的问题；需要时可使用下面的互斥锁策略。</p>
     *
     * @param keyPrefix 缓存 key 前缀，例如 cache:shop:
     * @param id 查询条件，同时用来拼接缓存 key，例如 1L
     * @param type 结果的具体类型，例如 Shop.class，供 JSON 反序列化使用
     * @param dbFallback 数据库查询回调，例如 this::getById；apply(id) 时才真正执行查询
     * @param time 正常业务数据的缓存有效期；空值缓存使用单独的 CACHE_NULL_TTL
     * @param unit 正常业务数据有效期的单位
     * @param <R> 返回的业务对象类型，例如 Shop
     * @param <ID> 查询条件的类型，例如 Long
     * @return 查询到的业务对象；命中空值缓存或数据库不存在时返回 null
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 例如 cache:shop: + 1，得到 cache:shop:1。
        // Redis GET 返回 null：key 不存在；返回 ""：key 存在，但标记了“数据库中没有此记录”。
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 2. 命中正常数据，把 JSON 转回调用方指定的类型，不再访问数据库。
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 3. 在本类约定的存储格式中，这里表示命中空字符串。
            // 不能直接把空字符串当成“缓存未命中”，否则空值缓存就失去了拦截回源的作用。
            return null;
        }

        // 4. 只有 key 不存在才执行数据库回调；工具类无需依赖具体的 ShopMapper 或业务 Service。
        R result = dbFallback.apply(id);
        if (result == null) {
            // 5. 数据库也没有：保存空字符串，并设置较短的有效期。
            // 短时间内重复请求同一个不存在的 id 会被缓存拦截；到期后仍允许重新检查数据库。
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库查到了：写入普通 JSON 缓存，再把本次查询结果返回给调用方。
        this.set(key, result, time, unit);
        return result;
    }

    /**
     * 使用互斥锁缓解缓存击穿：热点 key 失效时，在锁有效期内由持锁请求负责回源重建。
     * 没拿到锁的请求暂时休眠，再从“读缓存”开始重试；因此会增加这些请求的等待时间。
     * 参数含义与 queryWithPassThrough 相同，并复用它的空值缓存处理。
     *
     * <p>当前锁是带固定有效期的课程实现，没有自动续期；若重建耗时超过锁有效期，
     * 其他请求可能重新获得锁，因此不能把它理解为任意耗时下都只会执行一次数据库查询。</p>
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 同一个商户竞争同一把锁，不同商户可以分别重建。
        // 本例固定使用商户锁前缀；若复用到其他业务，还需要为锁划分相应的业务命名空间。
        String lockKey = LOCK_SHOP_KEY + id;
        while (true) {
            // 1. 每次重试先读缓存：等待期间，其他请求可能已经把数据写回 Redis。
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            // 2. 缓存不存在才尝试获取锁。每次尝试使用唯一 token，作为锁的所有权标记。
            String token = UUID.randomUUID().toString();
            if (!tryLock(lockKey, token)) {
                try {
                    // 3. 获取失败说明已有请求持锁，休眠 50 毫秒，避免持续空转争抢 Redis。
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // sleep 被中断后会清除中断标记，这里恢复标记并结束等待，让上层处理异常。
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待缓存重建被中断", e);
                }
                // 使用循环重新读缓存，避免递归重试不断增加调用栈；本次没有拿锁，也不能释放锁。
                continue;
            }

            try {
                // 4. 拿到锁后再次检查缓存（双重检查）。
                // 从第一次 GET 到成功获取锁之间，其他请求可能已经完成重建并释放了锁。
                // queryWithPassThrough 内部先查缓存，确实未命中才查数据库，还能处理空值缓存。
                return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
            } finally {
                // 5. 正常返回或抛出异常都要尝试释放自己持有的锁，避免其他请求无谓等待 TTL。
                unlock(lockKey, token);
            }
        }
    }

    /**
     * 使用逻辑过期缓解热点缓存击穿：优先保证请求快速返回，允许暂时读到旧数据。
     * 数据未过期直接返回；已过期则尝试提交后台重建任务，当前请求通常仍返回旧数据。
     *
     * <p>使用前必须调用 setWithLogicalExpire 预热热点数据。本方法遇到缓存不存在时直接返回 null，
     * 不会像普通缓存策略一样自动回源；缓存被删除后也需要重新预热。
     * 参数含义与 queryWithPassThrough 相同，其中 time/unit 表示重建后的逻辑有效期。</p>
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 1. key 不存在，或命中标记“不存在”的空值缓存，都直接返回 null。
        // 这是课程热点预热方案的约定，因此不能只切换查询方法而不准备对应的缓存数据。
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 2. 先解析外层包装对象，取得逻辑过期时间和业务数据。
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // RedisData.data 的声明类型是 Object，解析 JSON 后得到 JSONObject，需再转成具体业务类型。
        R result = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 3. 逻辑过期时间晚于现在，说明数据仍然有效，不需要竞争锁或访问数据库。
            return result;
        }

        // 4. 数据已经逻辑过期，尝试取得该商户的重建锁；未取得锁时直接走到末尾返回旧数据。
        String lockKey = LOCK_SHOP_KEY + id;
        String token = UUID.randomUUID().toString();
        if (tryLock(lockKey, token)) {
            // 标记“后台任务是否已成功提交”，用来确定由请求线程还是后台线程负责释放锁。
            boolean rebuildSubmitted = false;
            try {
                // 5. 获取锁后再读一次：在本请求读取旧值、尝试获取锁的间隙，
                // 其他任务可能已重建完毕，也可能有商户更新操作删除了缓存。
                String latestJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isBlank(latestJson)) {
                    // 缓存已缺失或变为空值标记时返回 null；finally 会负责释放本次取得的锁。
                    return null;
                }
                RedisData latestData = JSONUtil.toBean(latestJson, RedisData.class);
                if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 已被其他任务刷新，直接返回最新数据，不再重复提交重建任务。
                    return JSONUtil.toBean((JSONObject) latestData.getData(), type);
                }
                // 6. 确认仍然过期才提交任务。下面的 Lambda 由后台线程执行，当前线程不等待查询结果。
                cacheRebuildExecutor.submit(() -> {
                    try {
                        R fresh = dbFallback.apply(id);
                        if (fresh == null) {
                            // 商户可能已从数据库中删除，用短期空值缓存替换旧数据，避免后续继续返回旧商户。
                            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        } else {
                            // 写回最新数据，同时重新计算逻辑过期时间；正常数据仍不设置 Redis TTL。
                            this.setWithLogicalExpire(key, fresh, time, unit);
                        }
                    } catch (Exception e) {
                        // 重建异常发生在后台线程，记录日志以便排查；查询等步骤失败时不会主动清空旧数据。
                        log.error("异步重建缓存失败，key={}", key, e);
                    } finally {
                        // 后台工作结束后释放锁，无论重建成功还是失败都执行。
                        unlock(lockKey, token);
                    }
                });
                rebuildSubmitted = true;
            } finally {
                // 7. 提交成功后，锁交给后台任务释放，不能让当前线程提前删除它。
                // 若复查时提前返回、解析异常或提交失败，后台任务没有接手，必须在当前线程释放。
                if (!rebuildSubmitted) {
                    unlock(lockKey, token);
                }
            }
        }
        // 8. 没拿到锁，或已经提交后台任务，都返回一开始读到的旧数据，不等待数据库。
        // 这就是逻辑过期的取舍：请求响应更及时，但重建期间的数据可能不是最新的。
        return result;
    }

    /**
     * 尝试获取 Redis 锁，相当于 SET key token NX EX 有效秒数。
     * NX 表示 key 不存在时才写入；获取锁与设置 TTL 在同一条 Redis 命令中完成。
     * TTL 避免进程异常退出后锁永久残留，但不会自动续期，耗时过长仍可能失去锁。
     */
    private boolean tryLock(String key, String token) {
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // Boolean 可能为 null，使用工具方法避免直接拆箱造成空指针异常。
        return BooleanUtil.isTrue(locked);
    }

    // 执行 Lua 原子校验并删除锁。token 已不匹配时不删除，避免影响新的持锁请求。
    private void unlock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }

    // Spring 销毁该 Bean 时关闭执行器：停止接收新任务，已提交任务按 shutdown 的规则继续执行。
    @PreDestroy
    public void shutdown() {
        cacheRebuildExecutor.shutdown();
    }
}
