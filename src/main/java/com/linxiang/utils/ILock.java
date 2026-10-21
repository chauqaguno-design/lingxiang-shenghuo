package com.linxiang.utils;

/**
 * 锁接口：业务代码只关心“能否获得锁”和“使用完后释放锁”。
 *
 * <p>这里采用非阻塞方式：抢不到锁就立即返回 false，不会一直等待。
 * 调用方必须先判断 tryLock 的结果，只有获得锁的分支才能在 finally 中解锁。
 * 例如一人一单功能，未获得锁的请求应直接返回“请勿重复下单”。</p>
 */
public interface ILock {

    /**
     * 尝试获取锁，并在获取成功时设置自动过期时间。
     *
     * @param timeoutSec 锁的有效期，单位是秒；用于避免进程故障后锁永远无法释放
     * @return true 表示本次获得锁；false 表示没有获得锁，调用方不能执行受保护业务
     */
    boolean tryLock(long timeoutSec);

    /**
     * 尝试释放当前线程持有的锁。
     *
     * <p>SimpleRedisLock 使用 Lua，在一次原子操作中检查标识并删除自己的锁。</p>
     */
    void unlock();
}
