package com.linxiang.order;

import com.linxiang.dto.Result;
import com.linxiang.utils.RedisIdWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.*;

/**
 * 订单主流程：登记抢券请求 → Redis 预占 → MQ 异步建单 → 支付或超时关闭。
 * 请求状态：PREPARING 待预占、RESERVED 已预占、SUCCEEDED 已建单、FAILED 失败。
 * 订单状态：1 待支付、2 已支付、4 已取消；与请求状态分开保存。
 * 入口先写数据库，是为了故障后找回请求，因此这不是纯 Redis 的下单入口。
 */
@Service
public class OrderLifecycle {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ReservationStore reservations;
    private final RedisIdWorker ids;
    private final Clock clock;
    private final long ttl;
    public OrderLifecycle(JdbcTemplate db, PlatformTransactionManager manager, ReservationStore reservations,
                          RedisIdWorker ids, Clock clock, @Value("${app.order.ttl-seconds:600}") long ttl) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.reservations=reservations;
        this.ids=ids; this.clock=clock; this.ttl=ttl;
        if (ttl < 1) throw new IllegalArgumentException("Order TTL must be positive");
    }
    // MySQL 的 DATETIME 和 TIMESTAMP 可能返回不同 Java 类型，统一转成页面使用的时间文本。
    static String formatTime(Object value) {
        if(value instanceof LocalDateTime) return value.toString();
        if(value instanceof Timestamp) return ((Timestamp)value).toLocalDateTime().toString();
        throw new IllegalArgumentException("Unsupported database time type");
    }
    private Timestamp now() { return Timestamp.valueOf(LocalDateTime.now(clock)); }
    // 修改请求状态前使用 FOR UPDATE 锁住这条记录，避免多个线程同时处理同一请求。
    private Map<String,Object> request(long id, boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM seckill_request WHERE order_id=?"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    /** 接收抢券请求。返回订单号表示已受理，是否建单成功还需要查询结果。 */
    public Result submit(long user, long voucher, String clientKey) {
        // 1. 准备本次请求标识；客户端重试应继续携带同一个 X-Request-Id。
        if (clientKey == null || clientKey.trim().isEmpty()) clientKey=UUID.randomUUID().toString();
        if (!clientKey.matches("[A-Za-z0-9_-]{1,80}")) return Result.fail("请求标识格式不正确");
        // 2. 查找旧请求；没有才生成订单号并登记 PREPARING，避免网络重试创建多笔请求。
        List<Map<String,Object>> previous=db.queryForList("SELECT order_id,voucher_id FROM seckill_request WHERE user_id=? AND client_request_id=?",user,clientKey);
        long id;
        if (!previous.isEmpty()) {
            if (number(previous.get(0),"voucher_id")!=voucher) return Result.fail("请求标识已用于其他活动");
            id=number(previous.get(0),"order_id");
        } else {
            id=ids.nextId("order");
            try {
                db.update("INSERT INTO seckill_request(order_id,user_id,voucher_id,client_request_id,status,created_at,updated_at) VALUES(?,?,?,?,'PREPARING',?,?)",id,user,voucher,clientKey,now(),now());
            } catch (DuplicateKeyException concurrentRetry) {
                // 两个请求可能同时“没查到”；唯一键会拦住后插入的请求，再读取前一笔即可。
                Map<String,Object> existing=db.queryForMap("SELECT order_id,voucher_id FROM seckill_request WHERE user_id=? AND client_request_id=?",user,clientKey);
                if (number(existing,"voucher_id")!=voucher) return Result.fail("请求标识已用于其他活动");
                id=number(existing,"order_id");
            }
        }
        // 3. 尝试预占；Redis 断连时保留数据库请求，恢复任务可以用原订单号继续处理。
        try { prepare(id); } catch (org.springframework.data.redis.RedisConnectionFailureException unavailable) {
            // 此时不能把“连接失败”直接当作“没有扣库存”，Redis 可能已执行但响应未送达。
        }
        // 4. 返回失败原因或请求编号；不要把编号返回等同于“订单已经创建”。
        Map<String,Object> row=request(id,false);
        return "FAILED".equals(row.get("status")) ? Result.fail(String.valueOf(row.get("reason"))) : Result.ok(String.valueOf(id));
    }
    /** 预占名额，并把待发送的 CREATE 消息记入本地消息表。 */
    public void prepare(long id) {
        tx.execute(status -> {
            // 1. 锁住待处理请求；已经处理过的请求直接结束，保证重试不重复推进状态。
            Map<String,Object> row=request(id,true);
            if(row==null || !"PREPARING".equals(row.get("status"))) return null;
            // 2. Lua 校验活动、用户和库存；相同订单号再次调用不会重复预扣。
            // Redis 不在数据库事务里，后续 SQL 失败时靠原订单号重试来恢复。
            long code=reservations.reserve(id,number(row,"user_id"),number(row,"voucher_id"));
            if(code!=0) {
                String reason=code==1?"优惠券已抢完":code==2?"每人限购一张":code==6?"活动尚未开始":code==7?"活动已结束":code==5?"活动尚未就绪":"预占已释放";
                db.update("UPDATE seckill_request SET status='FAILED',reason=?,updated_at=? WHERE order_id=?",reason,now(),id);
            } else {
                // 3. 预占成功后，把请求状态和待发送消息放在同一个 MySQL 事务里提交。
                db.update("UPDATE seckill_request SET status='RESERVED',updated_at=? WHERE order_id=?",now(),id);
                event(id,"CREATE");
            }
            return null;
        });
    }
    private void event(long id, String type) {
        // outbox 是本地待办消息表：先随业务事务提交，再由 OrderJobs 发送或执行补偿。
        // 调用方持有请求或订单行锁；消息不能脱离业务状态单独提交。
        db.update("INSERT INTO message_outbox(order_id,event_type,status,retry_count,next_retry_at,created_at) VALUES(?,?,'NEW',0,?,?)",id,type,now(),now());
    }
    /** MQ 消费入口：数据库扣库存、插订单、登记关单消息必须一起成功或一起回滚。 */
    public void create(long id) {
        try {
            tx.execute(status -> {
                // 1. 锁住请求；重复消息看到状态已经变化，就不会再扣库存、建订单。
                Map<String,Object> row=request(id,true);
                if(row==null) throw new IllegalStateException("Request not found");
                if(!"RESERVED".equals(row.get("status"))) return null;
                long user=number(row,"user_id"), voucher=number(row,"voucher_id");
                // 2. 先查重复购买；并发下仍可能同时查到 0，所以还需要用户 + 券的唯一索引。
                Integer exists=db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=? AND voucher_id=?",Integer.class,user,voucher);
                if(exists!=null && exists>0) { failReserved(id,"每人限购一张"); return null; }
                // 3. 在同一条 SQL 中判断库存并扣减，避免先读库存、再扣减造成超卖。
                int changed=db.update("UPDATE tb_seckill_voucher SET stock=stock-1 WHERE voucher_id=? AND stock>0",voucher);
                if(changed!=1) { failReserved(id,"数据库库存不足"); return null; }
                // 4. 创建待支付订单，并设置支付截止时间。插入失败时，前面的库存扣减也回滚。
                Timestamp created=now(), expires=Timestamp.valueOf(created.toLocalDateTime().plusSeconds(ttl));
                db.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,pay_type,status,create_time,update_time,expire_time) VALUES(?,?,?,0,1,?,?,?)",id,user,voucher,created,created,expires);
                db.update("UPDATE seckill_request SET status='SUCCEEDED',updated_at=? WHERE order_id=?",created,id);
                // 5. 同一事务登记关单消息；提交后才会交给 MQ 延迟投递。
                event(id,"CLOSE");
                return null;
            });
        } catch (DuplicateKeyException duplicate) {
            // 联合唯一索引拦截了并发重复购买；先让原事务回滚，再开新事务登记失败和释放名额。
            // 不能在已失败的事务里继续补偿，也不能直接把 Redis 名额无条件加回去。
            tx.execute(status -> {
                Map<String,Object> row=request(id,true);
                if(row!=null && "RESERVED".equals(row.get("status"))) failReserved(id,"重复订单已拦截");
                return null;
            });
        }
    }
    /** 记录建单失败，交给 outbox 重试释放 Redis 预占，避免数据库提交前就释放名额。 */
    private void failReserved(long id,String reason) {
        db.update("UPDATE seckill_request SET status='FAILED',reason=?,updated_at=? WHERE order_id=?",reason,now(),id);
        event(id,"RELEASE_FAILED");
    }
    /** 关闭到期未支付订单。重复关单返回 false，不再增加数据库库存。 */
    public boolean close(long id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // 1. 只把“已到期 + 待支付”改成已取消；与支付争用同一订单行，只有一方能成功。
            int changed=db.update("UPDATE tb_voucher_order SET status=4,update_time=? WHERE id=? AND status=1 AND expire_time<=?",now(),id,now());
            if(changed==0) return false;
            // 2. 只有本次真正关闭成功，才回补 MySQL 库存；这两步在同一个事务中。
            Long voucher=db.queryForObject("SELECT voucher_id FROM tb_voucher_order WHERE id=?",Long.class,id);
            if(db.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?",voucher)!=1) throw new IllegalStateException("Voucher missing");
            // 3. 登记 Redis 回补待办；Lua 按订单记录防重，重复执行也不会重复增加名额。
            event(id,"RELEASE_CANCELLED");
            return true;
        }));
    }
    /** 模拟支付：只改本系统订单状态，没有对接真实支付渠道。 */
    public Result pay(long id,long user) {
        // 1. 一条条件 UPDATE 同时校验归属、状态和截止时间，避免检查后被关单线程抢先修改。
        int changed=db.update("UPDATE tb_voucher_order SET status=2,pay_time=?,update_time=? WHERE id=? AND user_id=? AND status=1 AND expire_time>?",now(),now(),id,user,now());
        if(changed==1) return Result.ok();
        // 2. 已支付订单再次提交仍返回成功；其他状态或过期订单不能付款。
        List<Integer> status=db.queryForList("SELECT status FROM tb_voucher_order WHERE id=? AND user_id=?",Integer.class,id,user);
        if(!status.isEmpty() && status.get(0)==2) return Result.ok();
        return Result.fail("订单不存在、已关闭或已过支付时间");
    }
    /** 查询自己的异步下单结果，建单后同时返回支付状态。 */
    public Result result(long id,long user) {
        // 1. 先校验归属，不能只凭订单号查询其他用户的数据。
        Map<String,Object> row=request(id,false);
        if(row==null || number(row,"user_id")!=user) return Result.fail("订单请求不存在");
        Map<String,Object> result=new LinkedHashMap<>();
        // 2. long 编号按字符串返回，避免 JavaScript 数字精度不足把订单号截断。
        result.put("id",String.valueOf(id)); result.put("voucherId",String.valueOf(row.get("voucher_id")));
        result.put("requestStatus",row.get("status")); result.put("reason",row.get("reason"));
        List<Map<String,Object>> orders=db.queryForList("SELECT status,expire_time FROM tb_voucher_order WHERE id=?",id);
        // 3. 请求可能还在排队；只有订单表已经落库，才补上订单状态和截止时间。
        if(!orders.isEmpty()) {
            result.put("orderStatus",orders.get(0).get("status"));
            result.put("expireTime",formatTime(orders.get(0).get("expire_time")));
        }
        return Result.ok(result);
    }
    /** 查询当前用户最近的 100 笔已创建订单。 */
    public Result mine(long user) {
        List<Map<String,Object>> rows=db.queryForList("SELECT id,voucher_id,status,create_time,expire_time FROM tb_voucher_order WHERE user_id=? ORDER BY create_time DESC LIMIT 100",user);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Map<String,Object> order=new LinkedHashMap<>();
            order.put("id",String.valueOf(row.get("id")));order.put("voucherId",String.valueOf(row.get("voucher_id")));order.put("status",row.get("status"));
            order.put("createTime",formatTime(row.get("create_time")));
            order.put("expireTime",formatTime(row.get("expire_time")));result.add(order);
        }
        return Result.ok(result);
    }
    /** 执行 outbox 的 Redis 回补待办；重复执行是否已释放由 Lua 判断。 */
    public void compensate(long id, boolean cancelled) {
        // 1. 从持久化请求取得用户和券，不依赖 MQ 后台线程中不存在的登录 ThreadLocal。
        Map<String,Object> row=request(id,false);
        if(row==null) throw new IllegalStateException("Request not found");
        // 2. 已建单后取消保留参与资格记录；建单失败则释放资格，允许用户重新尝试。
        reservations.release(id,number(row,"user_id"),number(row,"voucher_id"),cancelled);
    }
}
