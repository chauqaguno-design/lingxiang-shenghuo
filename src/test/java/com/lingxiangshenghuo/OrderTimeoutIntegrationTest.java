package com.lingxiangshenghuo;

import com.lingxiangshenghuo.entity.Voucher;
import com.lingxiangshenghuo.entity.VoucherOrder;
import com.lingxiangshenghuo.service.IVoucherOrderService;
import com.lingxiangshenghuo.service.IVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用本地 MySQL、Redis 验证关单；只创建、清理本测试自己的数据。
 */
@SpringBootTest(properties = {
        "lingxiangshenghuo.order.scan-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class OrderTimeoutIntegrationTest {

    @Autowired
    private IVoucherService voucherService;
    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private Long voucherId;
    private Long userId;
    private String stockKey;
    private String usersKey;
    private String returnedKey;

    @BeforeEach
    void prepareVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("关单集成测试券");
        voucher.setPayValue(100L);
        voucher.setActualValue(200L);
        voucher.setType(1);
        voucher.setStock(2);
        voucher.setBeginTime(LocalDateTime.now().minusDays(1));
        voucher.setEndTime(LocalDateTime.now().plusDays(1));
        voucherService.addSeckillVoucher(voucher);
        voucherId = voucher.getId();
        userId = 900000000L + voucherId;
        stockKey = "seckill:stock:" + voucherId;
        usersKey = "seckill:order:" + voucherId;
        returnedKey = "seckill:returned:" + voucherId;
    }

    @AfterEach
    void clearOwnData() {
        if (voucherId == null) {
            return;
        }
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
        redis.delete(Arrays.asList(stockKey, usersKey, returnedKey, stockKey + ":test-backup"));
    }

    @Test
    void timeoutOrderReturnsBothStocksOnlyOnce() {
        Long orderId = createOrder(userId);
        makeExpired(orderId);

        assertTrue(orderService.closeTimeoutOrder(orderId, 15));
        assertEquals(4, status(orderId));
        assertEquals(2, mysqlStock());
        assertEquals("1", redis.opsForValue().get(stockKey));

        orderService.returnRedisStock(orderId);
        assertFalse(orderService.closeTimeoutOrder(orderId, 15));
        orderService.returnRedisStock(orderId);

        assertEquals(2, mysqlStock());
        assertEquals("2", redis.opsForValue().get(stockKey));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey, userId.toString())));
        assertTrue(stockReturned(orderId));
    }

    @Test
    void freshAndPaidOrdersAreNotClosed() {
        Long freshId = createOrder(userId);
        Long paidId = createOrder(userId + 1);
        jdbc.update("UPDATE tb_voucher_order SET create_time = NOW() WHERE id = ?", freshId);
        makeExpired(paidId);
        jdbc.update("UPDATE tb_voucher_order SET status = 2, pay_time = NOW() WHERE id = ?", paidId);

        assertFalse(orderService.closeTimeoutOrder(freshId, 15));
        assertFalse(orderService.closeTimeoutOrder(paidId, 15));
        orderService.returnRedisStock(freshId);
        orderService.returnRedisStock(paidId);

        assertEquals(1, status(freshId));
        assertEquals(2, status(paidId));
        assertEquals(0, mysqlStock());
        assertEquals("0", redis.opsForValue().get(stockKey));
        assertFalse(stockReturned(freshId));
        assertFalse(stockReturned(paidId));
    }

    @Test
    void missingRedisStockCanBeRetriedAfterRecovery() {
        Long orderId = createOrder(userId);
        makeExpired(orderId);
        assertTrue(orderService.closeTimeoutOrder(orderId, 15));

        // 暂时移走库存 key，模拟 Redis 数据缺失，然后恢复原值重试。
        String backupKey = stockKey + ":test-backup";
        redis.rename(stockKey, backupKey);
        try {
            assertThrows(RuntimeException.class, () -> orderService.returnRedisStock(orderId));
            assertFalse(stockReturned(orderId));
            assertEquals(4, status(orderId));
            assertEquals(2, mysqlStock());
            assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey, userId.toString())));
        } finally {
            redis.rename(backupKey, stockKey);
        }

        orderService.returnRedisStock(orderId);
        assertTrue(stockReturned(orderId));
        assertEquals("2", redis.opsForValue().get(stockKey));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey, userId.toString())));
    }

    @Test
    void retryAfterRedisSuccessDoesNotReleaseTheUsersNewOrder() {
        Long oldOrderId = createOrder(userId);
        makeExpired(oldOrderId);
        assertTrue(orderService.closeTimeoutOrder(oldOrderId, 15));
        orderService.returnRedisStock(oldOrderId);

        // 模拟 Lua 已完成，但数据库的回补标记还没写成功。
        jdbc.update("UPDATE tb_voucher_order SET redis_stock_returned = 0 WHERE id = ?", oldOrderId);
        Long newOrderId = createOrder(userId);
        orderService.returnRedisStock(oldOrderId);

        assertEquals(1, status(newOrderId));
        assertEquals(1, mysqlStock());
        assertEquals("1", redis.opsForValue().get(stockKey));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey, userId.toString())));
        assertTrue(stockReturned(oldOrderId));
    }

    @Test
    void missingMysqlStockRollsBackTheOrderStatus() {
        Long orderId = createOrder(userId);
        makeExpired(orderId);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);

        assertThrows(RuntimeException.class, () -> orderService.closeTimeoutOrder(orderId, 15));

        assertEquals(1, status(orderId));
        assertFalse(stockReturned(orderId));
        assertEquals("1", redis.opsForValue().get(stockKey));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(usersKey, userId.toString())));
    }

    private Long createOrder(Long buyerId) {
        Long orderId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        Long result = redis.execute(script, Collections.emptyList(),
                voucherId.toString(), buyerId.toString(), orderId.toString());
        assertEquals(Long.valueOf(0), result);

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(buyerId);
        order.setVoucherId(voucherId);
        orderService.createVoucherOrder(order);
        assertNotNull(orderService.getById(orderId));
        return orderId;
    }

    private void makeExpired(Long orderId) {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE id = ?", orderId);
    }

    private int status(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
    }

    private int mysqlStock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
    }

    private boolean stockReturned(Long orderId) {
        return jdbc.queryForObject("SELECT redis_stock_returned FROM tb_voucher_order WHERE id = ?", Integer.class, orderId) == 1;
    }
}
