package com.linxiang.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linxiang.dto.Result;
import com.linxiang.entity.Shop;
import com.linxiang.mapper.ShopMapper;
import com.linxiang.service.IShopService;
import com.linxiang.service.cache.CacheTransactions;
import com.linxiang.utils.CacheClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.linxiang.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.linxiang.utils.RedisConstants.CACHE_SHOP_TTL;

/** 普通商户使用空值缓存；显式选定的热点商户使用逻辑过期与异步重建。 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    public static final String HOT_SHOP_KEY = "cache:shop:hot:";
    private static final long HOT_TTL_SECONDS = 30L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    private Set<Long> hotShopIds = Collections.emptySet();

    @Value("${app.shop-cache.hot-ids:}")
    public void configureHotShopIds(String ids) {
        Set<Long> selected = new HashSet<>();
        for (String id : ids.split(",")) {
            if (!id.trim().isEmpty()) {
                selected.add(Long.parseLong(id.trim()));
            }
        }
        hotShopIds = Collections.unmodifiableSet(selected);
    }

    @Override
    public Result queryById(Long id) {
        Shop shop;
        if (hotShopIds.contains(id)) {
            // 热点包装对象与普通 Shop JSON 用不同 key，切换配置时不会反序列化错格式。
            shop = cacheClient.queryWithLogicalExpire(
                    HOT_SHOP_KEY, id, Shop.class, this::getById, HOT_TTL_SECONDS, TimeUnit.SECONDS);
            if (shop == null) {
                // 第一次访问、更新删缓存后也能查询真实商户。互斥回源避免冷启动全打到数据库。
                shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class,
                        this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                if (shop != null) {
                    cacheClient.setWithLogicalExpire(HOT_SHOP_KEY + id, shop, HOT_TTL_SECONDS, TimeUnit.SECONDS);
                }
            }
        } else {
            // 不存在的商户缓存短时空串，减少非法 id 导致的持续数据库查询。
            shop = cacheClient.queryWithPassThrough(
                    CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        return shop == null ? Result.fail("店铺不存在！") : Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        if (!updateById(shop)) {
            return Result.fail("店铺不存在或更新失败");
        }
        // 方法内 UPDATE 执行完不等于事务已经提交。提交后再删，减少并发读回填旧值的窗口。
        // Redis 和 MySQL 仍不是一个事务；普通 TTL / 热点重建负责后续刷新，不宣称强一致。
        CacheTransactions.afterCommit(() -> {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            stringRedisTemplate.delete(HOT_SHOP_KEY + id);
        });
        return Result.ok();
    }
}
