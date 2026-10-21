package com.linxiang.order;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
/** 建单消费者：消息只携带订单号，用户与券的信息从持久化请求读取。 */
@Component
@RocketMQMessageListener(topic="linxiang-order-create",consumerGroup="linxiang-create-consumer")
public class CreateOrderConsumer implements RocketMQListener<String> {
    private final OrderLifecycle orders;
    public CreateOrderConsumer(OrderLifecycle orders) { this.orders=orders; }
    // 交给事务流程建单；不要捕获异常后返回成功，否则 MQ 会误以为消费完成而停止重试。
    @Override public void onMessage(String id) { orders.create(Long.parseLong(id)); }
}
