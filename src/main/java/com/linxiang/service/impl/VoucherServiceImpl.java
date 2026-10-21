package com.linxiang.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linxiang.dto.Result;
import com.linxiang.entity.SeckillVoucher;
import com.linxiang.entity.Voucher;
import com.linxiang.mapper.VoucherMapper;
import com.linxiang.service.ISeckillVoucherService;
import com.linxiang.service.IVoucherService;
import com.linxiang.service.cache.CacheTransactions;
import com.linxiang.service.cache.VoucherActivityCache;
import com.linxiang.service.cache.VoucherDisplayCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/** 基本券信息与秒杀配置分别保存，展示缓存与真正用于抢券的 Redis 名额分开管理。 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private VoucherDisplayCache voucherDisplayCache;
    @Resource
    private VoucherActivityCache voucherActivityCache;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        return Result.ok(voucherDisplayCache.queryByShop(shopId));
    }

    /** 普通券发布后也要失效店铺列表；失效动作不能早于数据库提交。 */
    @Override
    @Transactional
    public boolean save(Voucher voucher) {
        boolean saved = super.save(voucher);
        if (saved) {
            CacheTransactions.afterCommit(() -> voucherDisplayCache.evictShop(voucher.getShopId()));
        }
        return saved;
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("活动库存必须大于 0");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            throw new IllegalArgumentException("活动结束时间必须晚于开始时间");
        }
        voucher.setType(1);
        // 这里直接保存基础表，等两张表一起提交后再执行同一组缓存动作。
        if (getBaseMapper().insert(voucher) != 1) {
            throw new IllegalStateException("优惠券保存失败");
        }
        SeckillVoucher activity = new SeckillVoucher();
        activity.setVoucherId(voucher.getId());
        activity.setStock(voucher.getStock());
        activity.setBeginTime(voucher.getBeginTime());
        activity.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(activity)) {
            throw new IllegalStateException("秒杀信息保存失败");
        }
        // 两张表都提交之后才让活动可抢；事务回滚不会留下“只有 Redis 有库存”的幽灵活动。
        CacheTransactions.afterCommit(() -> {
            voucherActivityCache.initializeNewActivity(activity);
            voucherDisplayCache.evictShop(voucher.getShopId());
        });
    }
}
