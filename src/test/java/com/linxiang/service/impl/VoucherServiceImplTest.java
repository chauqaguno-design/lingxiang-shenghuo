package com.linxiang.service.impl;

import com.linxiang.dto.Result;
import com.linxiang.entity.SeckillVoucher;
import com.linxiang.entity.Voucher;
import com.linxiang.mapper.VoucherMapper;
import com.linxiang.service.ISeckillVoucherService;
import com.linxiang.service.cache.VoucherActivityCache;
import com.linxiang.service.cache.VoucherDisplayCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 模拟数据库操作，重点验证提交前不能开放 Redis 抢券库存。 */
class VoucherServiceImplTest {
    private VoucherServiceImpl service;
    private VoucherMapper voucherMapper;
    private ISeckillVoucherService seckillVoucherService;
    private VoucherActivityCache activityCache;
    private VoucherDisplayCache displayCache;

    @BeforeEach
    void setUp() {
        voucherMapper = mock(VoucherMapper.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        activityCache = mock(VoucherActivityCache.class);
        displayCache = mock(VoucherDisplayCache.class);
        service = new VoucherServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", voucherMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "voucherActivityCache", activityCache);
        ReflectionTestUtils.setField(service, "voucherDisplayCache", displayCache);
        when(voucherMapper.insert(any(Voucher.class))).thenAnswer(invocation -> {
            ((Voucher) invocation.getArgument(0)).setId(101L);
            return 1;
        });
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void initializesRedisOnlyAfterDatabaseCommitAndUsesGeneratedId() {
        beginTransaction();
        Voucher voucher = newSeckillVoucher();

        service.addSeckillVoucher(voucher);

        verifyNoInteractions(activityCache, displayCache);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        ArgumentCaptor<SeckillVoucher> captor = ArgumentCaptor.forClass(SeckillVoucher.class);
        InOrder order = inOrder(voucherMapper, seckillVoucherService, activityCache, displayCache);
        order.verify(voucherMapper).insert(voucher);
        order.verify(seckillVoucherService).save(captor.capture());
        order.verify(activityCache).initializeNewActivity(captor.getValue());
        order.verify(displayCache).evictShop(1L);
        assertEquals(101L, captor.getValue().getVoucherId());
        assertEquals(100, captor.getValue().getStock());
        assertEquals(voucher.getBeginTime(), captor.getValue().getBeginTime());
    }

    @Test
    void rollbackDoesNotCreateRedisStock() {
        beginTransaction();
        service.addSeckillVoucher(newSeckillVoucher());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(activityCache, displayCache);
    }

    @Test
    void failedBaseInsertDoesNotSaveExtensionOrPublishActivity() {
        when(voucherMapper.insert(any(Voucher.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.addSeckillVoucher(newSeckillVoucher()));
        verifyNoInteractions(seckillVoucherService, activityCache, displayCache);
    }

    @Test
    void failedExtensionSavePropagatesToTriggerDatabaseRollback() {
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.addSeckillVoucher(newSeckillVoucher()));
        verifyNoInteractions(activityCache, displayCache);
    }

    @Test
    void queryUsesDisplayCache() {
        Voucher display = new Voucher().setId(101L).setStock(null);
        when(displayCache.queryByShop(1L)).thenReturn(Collections.singletonList(display));

        Result result = service.queryVoucherOfShop(1L);

        assertTrue(result.getSuccess());
        assertEquals(Collections.singletonList(display), result.getData());
        verifyNoInteractions(voucherMapper);
    }

    @Test
    void ordinaryVoucherSaveEvictsDisplayOnlyAfterCommit() {
        beginTransaction();
        assertTrue(service.save(new Voucher().setShopId(1L).setType(0)));
        verifyNoInteractions(displayCache);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(displayCache).evictShop(1L);
    }

    @Test
    void invalidTimeRangeIsRejectedBeforeWritingAnything() {
        Voucher voucher = newSeckillVoucher();
        voucher.setEndTime(voucher.getBeginTime());
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verifyNoInteractions(voucherMapper, seckillVoucherService, activityCache);
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private Voucher newSeckillVoucher() {
        return new Voucher().setShopId(1L).setTitle("秒杀优惠券").setType(1)
                .setPayValue(5000L).setActualValue(10000L).setStock(100)
                .setBeginTime(LocalDateTime.of(2026, 10, 21, 10, 0))
                .setEndTime(LocalDateTime.of(2026, 10, 21, 12, 0));
    }
}
