package com.linxiang.service.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 数据库提交后才更新 Redis，避免其他请求在提交前重新缓存数据库旧值。 */
public final class CacheTransactions {
    private CacheTransactions() { }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // 非事务调用（例如单元测试）没有等待提交的必要。
            action.run();
        }
    }
}
