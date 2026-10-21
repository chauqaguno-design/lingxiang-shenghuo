package com.lingxiangshenghuo.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingxiangshenghuo.entity.Shop;
import com.lingxiangshenghuo.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商户 ID 的布隆过滤器：挡住大部分不存在的 ID，减少查询数据库。
 */
@Slf4j
@Component
public class ShopBloomFilter implements ApplicationRunner {

    public static final String SHOP_BLOOM_KEY = "bloom:shop:id";
    // 按预计 10 万个商户、0.1% 的误判率初始化；超过容量后需要重新评估并重建。
    private static final long EXPECTED_SHOPS = 100_000L;
    private static final double FALSE_PROBABILITY = 0.001;

    private final RedissonClient redissonClient;
    private final ShopMapper shopMapper;
    private volatile boolean ready = false;

    public ShopBloomFilter(RedissonClient redissonClient, ShopMapper shopMapper) {
        this.redissonClient = redissonClient;
        this.shopMapper = shopMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        ready = false;
        try {
            RBloomFilter<Long> filter = redissonClient.getBloomFilter(SHOP_BLOOM_KEY);
            filter.tryInit(EXPECTED_SHOPS, FALSE_PROBABILITY);
            // 只查 ID，不加载商户详情；保留已有过滤器，重复加入同一个 ID 不影响判断。
            List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>().select("id"));
            for (Shop shop : shops) {
                filter.add(shop.getId());
            }
            // 所有已有 ID 加入后才能拦截请求，不能拿空过滤器判断商户不存在。
            ready = true;
            log.info("商户布隆过滤器初始化完成，已导入 {} 个 ID", shops.size());
        } catch (Exception e) {
            log.warn("商户布隆过滤器初始化失败，暂时沿用缓存和数据库查询", e);
        }
    }

    public boolean mightContain(Long id) {
        if (!ready) {
            return true;
        }
        try {
            // 获取轻量包装对象，读取当前配置；不会重新创建 Redis 连接。
            RBloomFilter<Long> filter = redissonClient.getBloomFilter(SHOP_BLOOM_KEY);
            if (!filter.isExists()) {
                // Redis 中的过滤器丢失时放行，重启应用后重新导入已有 ID。
                ready = false;
                return true;
            }
            return filter.contains(id);
        } catch (Exception e) {
            ready = false;
            log.warn("商户布隆过滤器暂不可用，恢复为缓存和数据库查询", e);
            return true;
        }
    }

    public void add(Long id) {
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(SHOP_BLOOM_KEY);
        if (!filter.isExists()) {
            // 仅补一个新 ID 不能代表全部商户已经导入，因此继续放行查询。
            ready = false;
        }
        filter.tryInit(EXPECTED_SHOPS, FALSE_PROBABILITY);
        // 新增商户提交事务前加入；失败抛给调用方，让数据库事务回滚。
        filter.add(id);
    }
}
