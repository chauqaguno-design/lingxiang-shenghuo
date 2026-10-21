package com.linxiang.order;

import com.linxiang.dto.Result;
import com.linxiang.utils.RedisIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 使用真正的 H2 MySQL 模式数据库和 Spring 事务管理器，不 mock SQL 的成功或回滚。
 * Redis / ID 生成是外部边界：这里验证数据库状态机，Redis Lua 的原子性另由集成实验验证。
 */
class OrderLifecycleTest {
    private static final Instant START = Instant.parse("2026-10-21T04:00:00Z");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long VOUCHER = 10L;
    private DriverManagerDataSource dataSource;
    private Connection databaseLifetime;
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private ReservationStore reservations;
    private RedisIdWorker ids;
    private OrderLifecycle lifecycle;
    private ExecutorService workers;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:order_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        // 保留一个连接定义内存库寿命，测试结束关闭它即可释放数据库。
        // 不通过 JdbcTemplate 执行 SHUTDOWN：H2 关闭连接后 Spring 再读 Statement 警告会报错。
        databaseLifetime = dataSource.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("order-lifecycle-schema.sql")).execute(dataSource);
        db = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        reservations = mock(ReservationStore.class);
        ids = mock(RedisIdWorker.class);
        AtomicLong sequence = new AtomicLong(1000L);
        when(ids.nextId("order")).thenAnswer(invocation -> sequence.incrementAndGet());
        when(reservations.reserve(anyLong(), anyLong(), anyLong())).thenReturn(0L);
        lifecycle = lifecycleAt(db, START);
        workers = Executors.newFixedThreadPool(2);
        db.update("INSERT INTO tb_seckill_voucher(voucher_id,stock) VALUES(?,?)", VOUCHER, 3);
    }

    @AfterEach
    void tearDown() throws Exception {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        databaseLifetime.close();
    }

    @Test
    void duplicateCreateDeliveryCreatesOneOrderAndDeductsStockOnce() {
        insertReserved(1001L, 71L);

        lifecycle.create(1001L);
        lifecycle.create(1001L);

        assertEquals(2, stock());
        assertEquals(1, orderCount());
        assertEquals("SUCCEEDED", requestStatus(1001L));
        assertEquals(1, eventCount(1001L, "CLOSE"));
    }

    @Test
    void failedOrderInsertRollsBackEarlierStockDeductionAndCanBeRetried() {
        insertReserved(1001L, 99L);
        // 让真实 INSERT 在库存 UPDATE 之后失败；不能只 mock update 返回 false。
        db.execute("ALTER TABLE tb_voucher_order ADD CONSTRAINT injected_insert_failure CHECK(user_id<>99)");

        assertThrows(DataIntegrityViolationException.class, () -> lifecycle.create(1001L));

        assertEquals(3, stock());
        assertEquals(0, orderCount());
        assertEquals("RESERVED", requestStatus(1001L));
        assertEquals(0, eventCount(1001L, "CLOSE"));
        db.execute("ALTER TABLE tb_voucher_order DROP CONSTRAINT injected_insert_failure");
        lifecycle.create(1001L);
        assertEquals(2, stock());
        assertEquals(1, orderCount());
    }

    @Test
    void competingRequestsForSameUserAreStoppedByUniqueConstraintAfterBothPrechecksPass() throws Exception {
        insertReserved(1001L, 71L);
        insertReserved(1002L, 71L);
        // 两个 SQL COUNT 都真实读到 0 后才允许继续，使测试真正触发联合唯一约束的兜底路径。
        CyclicBarrier bothPrechecked = new CyclicBarrier(2);
        JdbcTemplate synchronizedReads = new BarrierJdbcTemplate(dataSource, bothPrechecked);
        OrderLifecycle competing = lifecycleAt(synchronizedReads, START);

        Future<?> first = workers.submit(() -> competing.create(1001L));
        Future<?> second = workers.submit(() -> competing.create(1002L));
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertEquals(2, stock(), "失败事务的第二次扣减必须回滚");
        assertEquals(1, orderCount());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM seckill_request WHERE status='SUCCEEDED'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM seckill_request WHERE status='FAILED'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='RELEASE_FAILED'", Integer.class));
        assertEquals("重复订单已拦截", db.queryForObject("SELECT reason FROM seckill_request WHERE status='FAILED'", String.class));
    }

    @Test
    void databaseStockExhaustionSchedulesReservationReleaseWithoutCreatingOrder() {
        db.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=?", VOUCHER);
        insertReserved(1001L, 71L);

        lifecycle.create(1001L);
        lifecycle.create(1001L);

        assertEquals(0, stock());
        assertEquals(0, orderCount());
        assertEquals("FAILED", requestStatus(1001L));
        assertEquals(1, eventCount(1001L, "RELEASE_FAILED"));
    }

    @Test
    void repeatedCloseRestoresDatabaseStockAndEmitsReleaseOnlyOnce() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);
        OrderLifecycle afterExpiry = lifecycleAt(db, START.plusSeconds(61));

        assertTrue(afterExpiry.close(1001L));
        assertFalse(afterExpiry.close(1001L));

        assertEquals(4, orderStatus(1001L));
        assertEquals(3, stock());
        assertEquals(1, eventCount(1001L, "RELEASE_CANCELLED"));
        assertFalse(afterExpiry.pay(1001L, 71L).getSuccess());
    }

    @Test
    void successfulPaymentIsIdempotentAndCannotBeClosedOrReleaseStock() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);

        assertTrue(lifecycle.pay(1001L, 71L).getSuccess());
        assertTrue(lifecycle.pay(1001L, 71L).getSuccess());
        assertFalse(lifecycleAt(db, START.plusSeconds(61)).close(1001L));

        assertEquals(2, orderStatus(1001L));
        assertEquals(2, stock());
        assertEquals(0, eventCount(1001L, "RELEASE_CANCELLED"));
    }

    @Test
    void concurrentPayAndCloseHaveOneWinningStateTransition() throws Exception {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);
        // 模拟付款请求在截止前到达、关单任务在截止后到达，并同时争用数据库行锁。
        OrderLifecycle payment = lifecycleAt(db, START.plusSeconds(59));
        OrderLifecycle closing = lifecycleAt(db, START.plusSeconds(61));
        CountDownLatch start = new CountDownLatch(1);
        Future<Result> pay = workers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return payment.pay(1001L, 71L);
        });
        Future<Boolean> close = workers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return closing.close(1001L);
        });
        start.countDown();
        boolean paid = pay.get(10, TimeUnit.SECONDS).getSuccess();
        boolean closed = close.get(10, TimeUnit.SECONDS);

        assertNotEquals(paid, closed, "支付和关单只能有一个成功");
        assertTrue(Arrays.asList(2, 4).contains(orderStatus(1001L)));
        assertEquals(paid ? 2 : 3, stock());
        assertEquals(paid ? 0 : 1, eventCount(1001L, "RELEASE_CANCELLED"));
        assertFalse(closing.close(1001L));
    }

    @Test
    void unauthorizedAndExpiredPaymentsCannotChangeOrder() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);

        assertFalse(lifecycle.pay(1001L, 72L).getSuccess());
        assertFalse(lifecycleAt(db, START.plusSeconds(60)).pay(1001L, 71L).getSuccess());

        assertEquals(1, orderStatus(1001L));
        assertEquals(2, stock());
    }

    @Test
    void sameClientRequestIdReturnsSameOrderAndReservesOnlyOnce() {
        Result first = lifecycle.submit(71L, VOUCHER, "request_abc");
        Result second = lifecycle.submit(71L, VOUCHER, "request_abc");

        assertTrue(first.getSuccess());
        assertEquals(first.getData(), second.getData());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM seckill_request", Integer.class));
        assertEquals(1, eventCount(1001L, "CREATE"));
        verify(reservations).reserve(1001L, 71L, VOUCHER);
        verify(ids).nextId("order");
        assertFalse(lifecycle.submit(71L, 11L, "request_abc").getSuccess());
    }

    @Test
    void rejectedReservationDoesNotEmitCreateOrDeductDatabaseStock() {
        when(reservations.reserve(anyLong(), anyLong(), anyLong())).thenReturn(2L);

        Result result = lifecycle.submit(71L, VOUCHER, "request_rejected");

        assertFalse(result.getSuccess());
        assertEquals("每人限购一张", result.getErrorMsg());
        assertEquals(3, stock());
        assertEquals("FAILED", requestStatus(1001L));
        assertEquals(0, eventCount(1001L, "CREATE"));
    }

    @Test
    void repeatedCompensationDelegatesSameIdentityWithoutAddingDatabaseStockAgain() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);
        OrderLifecycle closing = lifecycleAt(db, START.plusSeconds(61));
        closing.close(1001L);

        closing.compensate(1001L, true);
        closing.compensate(1001L, true);

        // 消息允许重复投递；真正 Redis 回补次数由 release-reservation.lua 的订单状态保护。
        verify(reservations, times(2)).release(1001L, 71L, VOUCHER, true);
        assertEquals(3, stock());
        assertEquals(1, eventCount(1001L, "RELEASE_CANCELLED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resultCanOnlyBeReadByOwnerAndSerializesLongIdAsString() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);

        assertFalse(lifecycle.result(1001L, 72L).getSuccess());
        Map<String, Object> result = (Map<String, Object>) lifecycle.result(1001L, 71L).getData();
        assertEquals("1001", result.get("id"));
        assertEquals("SUCCEEDED", result.get("requestStatus"));
        assertEquals(1, result.get("orderStatus"));
    }

    @Test
    void dateTimeFormattingAcceptsMysqlLocalDateTimeAndJdbcTimestamp() {
        LocalDateTime value = LocalDateTime.of(2026, 10, 21, 12, 34, 56);

        assertEquals("2026-10-21T12:34:56", OrderLifecycle.formatTime(value));
        assertEquals("2026-10-21T12:34:56", OrderLifecycle.formatTime(Timestamp.valueOf(value)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerOrderListContainsFormattedCreateAndExpiryTime() {
        insertReserved(1001L, 71L);
        lifecycle.create(1001L);

        java.util.List<Map<String, Object>> mine =
                (java.util.List<Map<String, Object>>) lifecycle.mine(71L).getData();

        assertEquals(1, mine.size());
        assertEquals("2026-10-21T12:00", mine.get(0).get("createTime"));
        assertEquals("2026-10-21T12:01", mine.get(0).get("expireTime"));
        assertTrue(((java.util.List<?>) lifecycle.mine(72L).getData()).isEmpty());
    }

    private OrderLifecycle lifecycleAt(JdbcTemplate jdbc, Instant instant) {
        return new OrderLifecycle(jdbc, manager, reservations, ids, Clock.fixed(instant, BUSINESS_ZONE), 60);
    }

    private void insertReserved(long id, long user) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.ofInstant(START, BUSINESS_ZONE));
        db.update("INSERT INTO seckill_request(order_id,user_id,voucher_id,client_request_id,status,created_at,updated_at)"
                + " VALUES(?,?,?,?,'RESERVED',?,?)", id, user, VOUCHER, "request_" + id, now, now);
    }

    private int stock() {
        return db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, VOUCHER);
    }

    private int orderCount() {
        return db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class);
    }

    private int orderStatus(long id) {
        return db.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, id);
    }

    private String requestStatus(long id) {
        return db.queryForObject("SELECT status FROM seckill_request WHERE order_id=?", String.class, id);
    }

    private int eventCount(long id, String type) {
        return db.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE order_id=? AND event_type=?",
                Integer.class, id, type);
    }

    private static final class BarrierJdbcTemplate extends JdbcTemplate {
        private final CyclicBarrier barrier;

        private BarrierJdbcTemplate(DataSource source, CyclicBarrier barrier) {
            super(source);
            this.barrier = barrier;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            T result = super.queryForObject(sql, requiredType, args);
            if (sql.startsWith("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id")) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("并发测试未能同时到达唯一约束检查前", exception);
                }
            }
            return result;
        }
    }
}
