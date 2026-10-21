package com.lingxiangshenghuo.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 【秒杀链路消息队列使用】-1：声明存放秒杀订单消息的队列。 */
@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_ORDER_QUEUE = "seckill-voucher-order";

    @Bean
    public Queue seckillOrderQueue() {
        // true 表示队列持久化，RabbitMQ 重启后队列仍然存在。
        return new Queue(SECKILL_ORDER_QUEUE, true);
    }
}
