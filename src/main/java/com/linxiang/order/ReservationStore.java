package com.linxiang.order;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/** Redis 抢券操作入口。预占成功只是取得排队资格，最终是否建单以数据库为准。 */
@Component
public class ReservationStore {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> reserve = script("seckill.lua");
    private final DefaultRedisScript<Long> release = script("release-reservation.lua");
    public ReservationStore(StringRedisTemplate redis) { this.redis = redis; }
    private static DefaultRedisScript<Long> script(String name) {
        // 加载 classpath 中的 Lua 文件，执行结果按 Long 接收。
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }
    /** 原子校验活动时间、一人一券和库存，并登记当前订单的预占记录。 */
    public long reserve(long order, long user, long voucher) {
        // 1. KEYS 依次是库存、活动时间、用户参与记录、订单预占记录；与 Lua 参数顺序一致。
        Long code = redis.execute(reserve, Arrays.asList("seckill:stock:" + voucher,
                "seckill:activity:" + voucher, "seckill:users:" + voucher,
                "seckill:reservation:" + order), String.valueOf(user), String.valueOf(order));
        // 2. 没有返回值属于服务异常，不能当作抢券成功；业务失败码交给订单流程解释。
        if (code == null) throw new IllegalStateException("Redis reservation returned no result");
        return code;
    }
    /** 释放预占名额。keepParticipation=true 表示已建单后取消，仍保留一人一券记录。 */
    public void release(long order, long user, long voucher, boolean keepParticipation) {
        // 1. Lua 检查订单预占状态，再加回库存、标记已释放，三步一次完成，防止重复回补。
        Long code = redis.execute(release, Arrays.asList("seckill:stock:" + voucher,
                "seckill:users:" + voucher, "seckill:reservation:" + order),
                String.valueOf(user), String.valueOf(order), keepParticipation ? "1" : "0");
        // 2. 0 表示此前已释放，可视为成功；负数或无响应表示无法确认，应保留待办继续排查。
        if (code == null || code < 0) throw new IllegalStateException("Reservation missing; reconciliation required");
    }
}
