package com.linxiang.order;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 后台任务：发送本地消息、执行库存回补、恢复未完成的预占、兜底关闭超时订单。
 * 消息发送成功后，标记 SENT 前仍可能宕机，所以允许重复投递，由消费者和 Lua 保证幂等。
 */
@Slf4j
@Component
public class OrderJobs {
    private final JdbcTemplate db;
    private final OrderLifecycle orders;
    private final RocketMQTemplate mq;
    private final int delay;
    private final org.redisson.api.RedissonClient redisson;
    public OrderJobs(JdbcTemplate db, OrderLifecycle orders, RocketMQTemplate mq,
                     org.redisson.api.RedissonClient redisson,
                     @Value("${app.order.close-delay-level:14}") int delay) {
        this.db=db;this.orders=orders;this.mq=mq;this.delay=delay;this.redisson=redisson;
    }
    @Scheduled(fixedDelay=300)
    public void relay() {
        // 1. 每批取 100 条到期可重试的待办，避免一次加载整张消息表。
        List<Map<String,Object>> events=db.queryForList("SELECT id,order_id,event_type FROM message_outbox WHERE status='NEW' AND next_retry_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 100");
        for(Map<String,Object> event:events) {
            long id=((Number)event.get("id")).longValue(), order=((Number)event.get("order_id")).longValue();
            String type=String.valueOf(event.get("event_type"));
            try {
                // 2. 回补事件直接调用 Redis Lua；建单和关单事件交给 RocketMQ。
                if(type.startsWith("RELEASE_")) orders.compensate(order,"RELEASE_CANCELLED".equals(type));
                else {
                    SendResult sent="CREATE".equals(type)
                            ? mq.syncSend("linxiang-order-create",String.valueOf(order))
                            : mq.syncSend("linxiang-order-close", MessageBuilder.withPayload(String.valueOf(order)).build(),3000,delay);
                    if(sent.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("Broker did not confirm SEND_OK");
                }
                // 3. 外部操作确认成功后再标记完成；此处失败会重试，因此外部操作必须幂等。
                db.update("UPDATE message_outbox SET status='SENT',last_error=NULL WHERE id=?",id);
            } catch(Exception error) {
                // 4. 失败后延迟 3 秒重试，并累计次数；错误只记录类型，避免日志泄漏请求或凭证。
                db.update("UPDATE message_outbox SET retry_count=retry_count+1,next_retry_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 3 SECOND),last_error=? WHERE id=?",error.getClass().getSimpleName(),id);
                log.warn("outbox {} {} retry: {}",id,type,error.getClass().getSimpleName());
            }
        }
    }
    @Scheduled(fixedDelay=3000)
    public void recover() {
        // 1. 多实例竞争扫描锁，只让一个实例工作。未设置固定持锁时长，由 Redisson 看门狗续期。
        // 锁只减少重复扫描，正确性仍靠数据库状态判断；不能把“不重复回补”只寄托在锁上。
        org.redisson.api.RLock lock=redisson.getLock("linxiang:recovery-lock");
        if(!lock.tryLock()) return;
        try {
        // 2. 恢复待预占请求；Redis 已预占而数据库未提交时，使用同一订单号重试不会再次扣减。
        for(Long id:db.queryForList("SELECT order_id FROM seckill_request WHERE status='PREPARING' ORDER BY updated_at LIMIT 100",Long.class)) {
            try { orders.prepare(id); } catch(Exception error) { log.warn("prepare retry {}: {}",id,error.getClass().getSimpleName()); }
        }
        // 3. 消息已发送但超过 30 秒还没建单时，安排重新投递 CREATE；最多处理 100 条。
        // 设定下一次发送时间，避免每次扫描都重投。消费者的请求状态和唯一键继续负责防重。
        for(Long eventId:db.queryForList("SELECT o.id FROM message_outbox o JOIN seckill_request r ON r.order_id=o.order_id WHERE o.status='SENT' AND o.event_type='CREATE' AND o.next_retry_at<=CURRENT_TIMESTAMP AND r.status='RESERVED' AND r.updated_at<=CURRENT_TIMESTAMP - INTERVAL '30' SECOND ORDER BY o.id LIMIT 100",Long.class)) {
            try {
                // 更新时再检查请求状态，避免 SELECT 后消费者已成功建单仍被重新加入队列。
                db.update("UPDATE message_outbox SET status='NEW',next_retry_at=CURRENT_TIMESTAMP + INTERVAL '30' SECOND WHERE id=? AND status='SENT' AND event_type='CREATE' AND next_retry_at<=CURRENT_TIMESTAMP AND EXISTS (SELECT 1 FROM seckill_request r WHERE r.order_id=message_outbox.order_id AND r.status='RESERVED' AND r.updated_at<=CURRENT_TIMESTAMP - INTERVAL '30' SECOND)",eventId);
            } catch(Exception error) { log.warn("create delivery recovery {}: {}",eventId,error.getClass().getSimpleName()); }
        }
        // 4. 扫描已到期的待支付订单，作为延迟消息丢失、延后或提前触发时的关单兜底。
        for(Long id:db.queryForList("SELECT id FROM tb_voucher_order WHERE status=1 AND expire_time<=CURRENT_TIMESTAMP ORDER BY expire_time LIMIT 100",Long.class)) {
            try { orders.close(id); } catch(Exception error) { log.warn("close retry {}: {}",id,error.getClass().getSimpleName()); }
        }
        } finally {
            // 5. 无论扫描是否异常都释放自己的锁；锁已失效时不尝试解锁其他实例的锁。
            if(lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
