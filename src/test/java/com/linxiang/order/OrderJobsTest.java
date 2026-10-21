package com.linxiang.order;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实数据库验证补投范围、限量和冷却时间，MQ 与扫描锁只作为外部边界模拟。 */
class OrderJobsTest {
    private DriverManagerDataSource source;
    private Connection lifetime;
    private JdbcTemplate db;
    private OrderLifecycle orders;
    private RocketMQTemplate mq;
    private RedissonClient redisson;
    private RLock lock;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource("jdbc:h2:mem:recovery_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        lifetime = source.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("order-lifecycle-schema.sql")).execute(source);
        db = new JdbcTemplate(source);
        orders = mock(OrderLifecycle.class);
        mq = mock(RocketMQTemplate.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock("linxiang:recovery-lock")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        lifetime.close();
    }

    @Test
    void sentCreateForOldReservedRequestIsRequeuedWithThirtySecondCooldown() {
        seed(1001L, "RESERVED", 60, -1);
        LocalDateTime before = LocalDateTime.now();

        jobs(db).recover();

        assertEquals("NEW", status(1001L));
        Timestamp next = db.queryForObject("SELECT next_retry_at FROM message_outbox WHERE order_id=?", Timestamp.class, 1001L);
        assertTrue(next.toLocalDateTime().isAfter(before.plusSeconds(28)));
        assertTrue(next.toLocalDateTime().isBefore(LocalDateTime.now().plusSeconds(32)));
        verify(lock).unlock();
        verifyNoInteractions(mq);
    }

    @Test
    void freshCompletedFailedAndCoolingRequestsAreNotRequeued() {
        seed(1001L, "RESERVED", 5, -1);
        seed(1002L, "SUCCEEDED", 60, -1);
        seed(1003L, "FAILED", 60, -1);
        seed(1004L, "RESERVED", 60, 60);

        jobs(db).recover();

        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status='SENT'", Integer.class));
    }

    @Test
    void recoveryBatchIsLimitedToOneHundredEvents() {
        for (long id = 1001L; id <= 1105L; id++) {
            seed(id, "RESERVED", 60, -1);
        }

        jobs(db).recover();

        assertEquals(100, db.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status='NEW'", Integer.class));
        assertEquals(5, db.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status='SENT'", Integer.class));
    }

    @Test
    void requestCompletedAfterSelectionIsNotRequeued() {
        seed(1001L, "RESERVED", 60, -1);
        JdbcTemplate completingConsumer = new JdbcTemplate(source) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> type) {
                List<T> rows = super.queryForList(sql, type);
                if (sql.startsWith("SELECT o.id FROM message_outbox")) {
                    // 模拟消费者在候选查询结束后、补投 UPDATE 前完成订单。
                    db.update("UPDATE seckill_request SET status='SUCCEEDED' WHERE order_id=1001");
                }
                return rows;
            }
        };

        jobs(completingConsumer).recover();

        assertEquals("SENT", status(1001L));
    }

    @Test
    void anotherScanDuringCooldownDoesNotAdvanceOrDuplicateExistingEvent() {
        seed(1001L, "RESERVED", 60, -1);
        OrderJobs jobs = jobs(db);
        jobs.recover();
        Timestamp firstRetry = db.queryForObject("SELECT next_retry_at FROM message_outbox WHERE order_id=1001", Timestamp.class);
        // 即使另一个发送线程已经标记 SENT，冷却时间未到也不能再次排队。
        db.update("UPDATE message_outbox SET status='SENT' WHERE order_id=1001");

        jobs.recover();

        assertEquals("SENT", status(1001L));
        assertEquals(firstRetry, db.queryForObject("SELECT next_retry_at FROM message_outbox WHERE order_id=1001", Timestamp.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM message_outbox", Integer.class));
    }

    private OrderJobs jobs(JdbcTemplate jdbc) {
        return new OrderJobs(jdbc, orders, mq, redisson, 14);
    }

    private String status(long order) {
        return db.queryForObject("SELECT status FROM message_outbox WHERE order_id=?", String.class, order);
    }

    private void seed(long order, String requestStatus, long ageSeconds, long retryAfterSeconds) {
        LocalDateTime now = LocalDateTime.now();
        Timestamp created = Timestamp.valueOf(now.minusSeconds(ageSeconds));
        db.update("INSERT INTO seckill_request(order_id,user_id,voucher_id,client_request_id,status,created_at,updated_at) VALUES(?,?,10,?,?,?,?)",
                order, order, "request_" + order, requestStatus, created, created);
        db.update("INSERT INTO message_outbox(order_id,event_type,status,retry_count,next_retry_at,created_at) VALUES(?,'CREATE','SENT',0,?,?)",
                order, Timestamp.valueOf(now.plusSeconds(retryAfterSeconds)), created);
    }
}
