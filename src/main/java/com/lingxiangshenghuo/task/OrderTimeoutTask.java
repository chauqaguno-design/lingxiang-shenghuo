package com.lingxiangshenghuo.task;

import com.lingxiangshenghuo.entity.VoucherOrder;
import com.lingxiangshenghuo.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 定时关闭超时未支付订单，并回补库存。
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Value("${lingxiangshenghuo.order.timeout-minutes:15}")
    private int timeoutMinutes;

    @Scheduled(fixedDelayString = "${lingxiangshenghuo.order.scan-interval-ms:30000}",
            initialDelayString = "${lingxiangshenghuo.order.scan-interval-ms:30000}")
    public void closeTimeoutOrders() {
        // 1.每轮最多找 100 个超时未支付订单，避免一次处理过多。
        List<VoucherOrder> expiredOrders = voucherOrderService.lambdaQuery()
                .select(VoucherOrder::getId)
                .eq(VoucherOrder::getStatus, 1)
                .eq(VoucherOrder::getRedisStockReturned, false)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} MINUTE)", timeoutMinutes)
                .orderByAsc(VoucherOrder::getCreateTime)
                .last("LIMIT 100")
                .list();
        for (VoucherOrder order : expiredOrders) {
            try {
                // 调用另一个 Bean 的方法，普通 @Transactional 会生效。
                if (voucherOrderService.closeTimeoutOrder(order.getId(), timeoutMinutes)) {
                    log.info("超时订单已关闭，订单ID={}", order.getId());
                }
            } catch (Exception e) {
                log.warn("关单失败，下轮重试，订单ID={}", order.getId(), e);
            }
        }

        // 2.找出已经关闭、但 Redis 库存尚未回补的订单，包含上轮失败的记录。
        List<VoucherOrder> pendingOrders = voucherOrderService.lambdaQuery()
                .select(VoucherOrder::getId)
                .eq(VoucherOrder::getStatus, 4)
                .eq(VoucherOrder::getRedisStockReturned, false)
                .orderByAsc(VoucherOrder::getCreateTime)
                .last("LIMIT 100")
                .list();
        for (VoucherOrder order : pendingOrders) {
            try {
                voucherOrderService.returnRedisStock(order.getId());
            } catch (Exception e) {
                log.warn("Redis库存回补失败，下轮重试，订单ID={}", order.getId(), e);
            }
        }
    }
}
