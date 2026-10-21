package com.linxiang.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    // 空值缓存有效期：2 分钟。在 CacheClient 中明确使用 MINUTES 写入，避免不存在的 id 反复查库。
    // 有效期较短，便于数据库后来新增该记录时，在空值缓存过期后重新查到它。
    public static final Long CACHE_NULL_TTL = 2L;

    // 普通商户缓存有效期：ShopServiceImpl 调用时指定 MINUTES，因此这里的 30 表示 30 分钟。
    public static final Long CACHE_SHOP_TTL = 30L;
    // 商户详情缓存前缀，与 id 拼接后得到完整 key，例如 cache:shop:1。
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    // 商户缓存重建锁前缀，例如 lock:shop:1；锁 key 与实际缓存 key 分开保存。
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    // 重建锁有效期：10 秒，tryLock 中使用 SECONDS。这里是锁的存活时间，不是数据缓存的有效期。
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String CACHE_SHOP_TYPE_LIST = "cache:shop:type:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
}
