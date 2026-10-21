package com.linxiang.order;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
/** 关单消费者：延迟消息只是检查时机，能否关闭由数据库中的状态与截止时间决定。 */
@Component
@RocketMQMessageListener(topic="linxiang-order-close",consumerGroup="linxiang-close-consumer")
public class CloseOrderConsumer implements RocketMQListener<String> {
    private final OrderLifecycle orders;
    public CloseOrderConsumer(OrderLifecycle orders) { this.orders=orders; }
    // 重复消息、已支付订单都不会重复回补；若消息提前到达，由定时扫描在真正到期后再关闭。
    @Override public void onMessage(String id) { orders.close(Long.parseLong(id)); }
}
