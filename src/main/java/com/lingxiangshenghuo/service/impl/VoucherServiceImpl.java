package com.lingxiangshenghuo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingxiangshenghuo.dto.Result;
import com.lingxiangshenghuo.entity.SeckillVoucher;
import com.lingxiangshenghuo.entity.Voucher;
import com.lingxiangshenghuo.mapper.VoucherMapper;
import com.lingxiangshenghuo.service.ISeckillVoucherService;
import com.lingxiangshenghuo.service.IVoucherService;
import com.lingxiangshenghuo.service.cache.VoucherListCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.lingxiangshenghuo.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherListCacheService voucherListCacheService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 【秒杀优惠券信息多级缓存改造】查询优惠券信息（L1 Caffeine + L2 Redis）
        List<Voucher> vouchers = voucherListCacheService.getVoucherByShopId(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到 Redis，抢券时 Lua 从这里判断和扣减库存。
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}
