package com.lingxiangshenghuo.service;

import com.lingxiangshenghuo.dto.Result;
import com.lingxiangshenghuo.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    // 关闭超时未支付订单，同时回补 MySQL 库存。
    boolean closeTimeoutOrder(Long orderId, int timeoutMinutes);

    // 回补已关闭订单的 Redis 库存，重复调用不会重复增加库存。
    void returnRedisStock(Long orderId);
}
