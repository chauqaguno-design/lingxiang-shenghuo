package com.lingxiangshenghuo;

import com.lingxiangshenghuo.entity.VoucherOrder;
import com.lingxiangshenghuo.service.IVoucherOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 MySQL 验证建单事务和重复消费，只清理本测试创建的数据。
 */
@SpringBootTest(properties = {
        "lingxiangshenghuo.order.scan-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class OrderCreateIntegrationTest {

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long voucherId;
    private Long orderId;
    private boolean prepared;

    @BeforeEach
    void prepareStock() {
        voucherId = randomId();
        orderId = randomId();
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                        + "VALUES (?, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        prepared = true;
    }

    @AfterEach
    void clearOwnData() {
        if (prepared) {
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        }
    }

    @Test
    void createsOrderAndDeductsOneStock() {
        orderService.createVoucherOrder(newOrder());

        assertEquals(1, stock());
        assertEquals(1, orderCount());
        assertEquals(Integer.valueOf(1), orderService.getById(orderId).getStatus());
    }

    @Test
    void repeatedDeliveryDoesNotDeductStockAgain() {
        orderService.createVoucherOrder(newOrder());
        orderService.createVoucherOrder(newOrder());

        assertEquals(1, stock());
        assertEquals(1, orderCount());
    }

    @Test
    void replayDoesNotReopenCancelledOrder() {
        orderService.createVoucherOrder(newOrder());
        jdbc.update("UPDATE tb_voucher_order SET status = 4 WHERE id = ?", orderId);

        orderService.createVoucherOrder(newOrder());

        assertEquals(Integer.valueOf(4), orderService.getById(orderId).getStatus());
        assertEquals(1, stock());
        assertEquals(1, orderCount());
    }

    @Test
    void insertFailureRollsBackStockDeduction() {
        // user_id 不能为空，故意让保存订单失败，检查前面的扣库存是否回滚。
        VoucherOrder invalidOrder = newOrder().setUserId(null);

        assertThrows(DataIntegrityViolationException.class,
                () -> orderService.createVoucherOrder(invalidOrder));

        assertEquals(2, stock());
        assertEquals(0, orderCount());
    }

    @Test
    void insufficientStockThrowsWithoutCreatingOrder() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 0 WHERE voucher_id = ?", voucherId);

        assertThrows(IllegalStateException.class,
                () -> orderService.createVoucherOrder(newOrder()));

        assertEquals(0, stock());
        assertEquals(0, orderCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void concurrentDeliveryAndRetryOnlyCreateOneOrder(int initialStock) throws Exception {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?", initialStock, voucherId);
        int consumers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < consumers; i++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待并发测试开始超时");
                    }
                    try {
                        orderService.createVoucherOrder(newOrder());
                    } catch (DuplicateKeyException e) {
                        // 并发插入撞到主键时，事务先回滚；再次消费就能查到已有订单。
                        orderService.createVoucherOrder(newOrder());
                    } catch (IllegalStateException e) {
                        // 只有一张券时，另一个消费者先建单，本次也可能直接扣库存失败。
                        assertEquals("库存不足，创建订单失败，订单ID=" + orderId, e.getMessage());
                        orderService.createVoucherOrder(newOrder());
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(initialStock - 1, stock());
        assertEquals(1, orderCount());
        assertNotNull(orderService.getById(orderId));
    }

    private VoucherOrder newOrder() {
        return new VoucherOrder().setId(orderId).setVoucherId(voucherId).setUserId(voucherId + 1);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucherId);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, voucherId);
    }

    private long randomId() {
        return ThreadLocalRandom.current().nextLong(1000000000000L, 8000000000000L);
    }
}
