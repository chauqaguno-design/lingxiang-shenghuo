package com.linxiang.service.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.linxiang.config.VoucherCacheProperties;
import com.linxiang.entity.Voucher;
import com.linxiang.mapper.VoucherMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 这里只缓存店铺的券标题、价格和活动时间等展示数据，绝不能用这里的内容决定能否抢到券。
 * stock 始终清空：真实抢券名额由抢券 Lua / MySQL 条件扣减共同控制。
 * L1 存 JSON 字符串，返回时新建对象，防止调用者修改一个对象后污染所有请求。
 */
@Service
public class VoucherDisplayCache {
    public static final String KEY_PREFIX = "cache:voucher:shop:";

    private final VoucherMapper voucherMapper;
    private final StringRedisTemplate redis;
    private final VoucherCacheProperties properties;
    private final String mode;
    private final Cache<Long, String> local;

    public VoucherDisplayCache(VoucherMapper voucherMapper, StringRedisTemplate redis,
                               VoucherCacheProperties properties) {
        this.voucherMapper = voucherMapper;
        this.redis = redis;
        this.properties = properties;
        this.mode = properties.getMode().toLowerCase(Locale.ROOT);
        if (!"mysql".equals(mode) && !"redis".equals(mode) && !"caffeine".equals(mode)) {
            throw new IllegalArgumentException("app.voucher-cache.mode 只能是 mysql、redis 或 caffeine");
        }
        if (properties.getCapacity() <= 0 || properties.getLocalTtlSeconds() <= 0
                || properties.getRedisTtlSeconds() <= 0 || properties.getEmptyTtlSeconds() <= 0) {
            throw new IllegalArgumentException("券缓存容量和有效期必须大于 0");
        }
        local = Caffeine.newBuilder().maximumSize(properties.getCapacity())
                .expireAfter(new Expiry<Long, String>() {
                    @Override
                    public long expireAfterCreate(Long key, String value, long currentTime) {
                        return localTtl(value);
                    }

                    @Override
                    public long expireAfterUpdate(Long key, String value, long currentTime, long currentDuration) {
                        return localTtl(value);
                    }

                    @Override
                    public long expireAfterRead(Long key, String value, long currentTime, long currentDuration) {
                        // 读取不能延长缓存寿命，否则持续热门的数据可能一直不失效。
                        return currentDuration;
                    }
                }).build();
    }

    public List<Voucher> queryByShop(Long shopId) {
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("商户 id 必须大于 0");
        }
        if ("mysql".equals(mode)) {
            return readDisplayFromDatabase(shopId);
        }
        // Caffeine.get 会合并同一个 JVM 中同 key 的并发加载，减少本机回源风暴。
        String json = "caffeine".equals(mode)
                ? local.get(shopId, this::loadRedisOrDatabase) : loadRedisOrDatabase(shopId);
        List<Voucher> vouchers = JSONUtil.toList(JSONUtil.parseArray(json), Voucher.class);
        // 即使 Redis 曾存过旧格式，也不把陈旧库存交给前端当作可购买名额。
        vouchers.forEach(voucher -> voucher.setStock(null));
        return vouchers;
    }

    private String loadRedisOrDatabase(Long shopId) {
        String key = KEY_PREFIX + shopId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }
        List<Voucher> vouchers = readDisplayFromDatabase(shopId);
        String json = JSONUtil.toJsonStr(vouchers);
        long ttl = vouchers.isEmpty() ? properties.getEmptyTtlSeconds() : properties.getRedisTtlSeconds();
        redis.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
        return json;
    }

    private List<Voucher> readDisplayFromDatabase(Long shopId) {
        List<Voucher> rows = voucherMapper.queryVoucherOfShop(shopId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Voucher> displays = new ArrayList<>(rows.size());
        for (Voucher row : rows) {
            Voucher display = new Voucher();
            BeanUtils.copyProperties(row, display);
            display.setStock(null);
            displays.add(display);
        }
        return displays;
    }

    private long localTtl(String value) {
        long seconds = "[]".equals(value)
                ? Math.min(properties.getLocalTtlSeconds(), properties.getEmptyTtlSeconds())
                : properties.getLocalTtlSeconds();
        return TimeUnit.SECONDS.toNanos(seconds);
    }

    /**
     * 调用方在数据库提交后执行。其他实例的 L1 最长在 localTtlSeconds 后失效，
     * 因而这个展示缓存是有界的最终一致性，不承诺跨实例立即看到修改。
     */
    public void evictShop(Long shopId) {
        if (shopId != null) {
            local.invalidate(shopId);
            redis.delete(KEY_PREFIX + shopId);
        }
    }
}
