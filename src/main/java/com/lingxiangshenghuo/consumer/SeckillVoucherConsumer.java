package com.lingxiangshenghuo.consumer;

import cn.hutool.json.JSONUtil;
import com.lingxiangshenghuo.config.RabbitMQConfig;
import com.lingxiangshenghuo.entity.VoucherOrder;
import com.lingxiangshenghuo.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class SeckillVoucherConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 【秒杀链路消息队列使用】-3
     * 监听秒杀订单队列，将消息交给原来的建单方法处理。
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void processMessage(String message) {
        try {
            // 1. 将生产者发来的 JSON 转回订单对象。
            VoucherOrder order = JSONUtil.toBean(message, VoucherOrder.class);
            // 2. 沿用原来的扣库存、创建订单逻辑。
            voucherOrderService.createVoucherOrder(order);
            // 3. 方法正常结束后，Spring 自动确认消息，不需要手动调用 ack。
        } catch (Exception e) {
            log.error("秒杀订单消息消费异常", e);
            // 异常继续抛出，不能让 RabbitMQ 误以为消息已经处理成功。
            throw new RuntimeException("秒杀订单消息消费失败", e);
        }
    }
}
