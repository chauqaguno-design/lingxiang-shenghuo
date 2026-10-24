package com.lingxiangshenghuo.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingxiangshenghuo.config.RabbitMQConfig;
import com.lingxiangshenghuo.dto.Result;
import com.lingxiangshenghuo.entity.VoucherOrder;
import com.lingxiangshenghuo.mapper.VoucherOrderMapper;
import com.lingxiangshenghuo.service.ISeckillVoucherService;
import com.lingxiangshenghuo.service.IVoucherOrderService;
import com.lingxiangshenghuo.utils.RedisIdWorker;
import com.lingxiangshenghuo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Arrays;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> RETURN_STOCK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        RETURN_STOCK_SCRIPT = new DefaultRedisScript<>();
        RETURN_STOCK_SCRIPT.setLocation(new ClassPathResource("return_stock.lua"));
        RETURN_STOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果
        if(result == 1){
            return Result.fail("库存不足");
        }
        if(result == 2){
            return Result.fail("重复下单");
        }
        // 有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);    // 订单ID
        voucherOrder.setUserId(userId); // 用户ID
        voucherOrder.setVoucherId(voucherId); // 优惠券ID

        // 3.发送 RabbitMQ 消息  【秒杀链路消息队列使用】-2
        // 使用默认交换机，按队列名投递；订单转成 JSON，消费者再转回订单对象。
        rabbitTemplate.convertAndSend(RabbitMQConfig.SECKILL_ORDER_QUEUE, JSONUtil.toJsonStr(voucherOrder));

        // 4.返回订单id
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.同一个订单 ID 已处理过，直接结束。已取消的订单也不能重新创建。
        if (getById(voucherOrder.getId()) != null) {
            return;
        }

        // 2.数据库库存大于 0 才能扣减，扣减失败就抛异常，不能当成消费成功。
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            throw new IllegalStateException("库存不足，创建订单失败，订单ID=" + voucherOrder.getId());
        }

        // 3.保存订单。与扣库存处于同一个事务，保存失败时库存也会回滚。
        // 两条重复消息同时执行到这里时，由订单主键兜底，冲突的一次整体回滚。
        if (!save(voucherOrder)) {
            throw new IllegalStateException("保存订单失败，订单ID=" + voucherOrder.getId());
        }
    }

    @Override
    @Transactional
    public boolean closeTimeoutOrder(Long orderId, int timeoutMinutes) {
        // 1.查询订单，取得优惠券 ID。
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return false;
        }
        // 2.只关闭“未支付且已超时”的订单；已支付、已关闭的订单不会修改。
        // 时间由 MySQL 计算，和订单创建时间使用同一个时区。
        boolean closed = lambdaUpdate()
                .set(VoucherOrder::getStatus, 4)
                .set(VoucherOrder::getRedisStockReturned, false)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 1)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} MINUTE)", timeoutMinutes)
                .update();
        if (!closed) {
            return false;
        }
        // 3.只有本次真正关单成功，才给 MySQL 库存加 1。
        // 这两次数据库修改放在一个事务中，要么一起成功，要么一起回滚。
        boolean returned = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!returned) {
            throw new IllegalStateException("优惠券不存在，关单回滚，订单ID=" + orderId);
        }
        return true;
    }

    @Override
    public void returnRedisStock(Long orderId) {
        // 1.只处理已取消、Redis 库存还没回补的订单。
        VoucherOrder order = getById(orderId);
        if (order == null || !Integer.valueOf(4).equals(order.getStatus())
                || Boolean.TRUE.equals(order.getRedisStockReturned())) {
            return;
        }
        // 2.Lua 一次完成：库存加 1、释放用户抢券资格、记录这个订单已回补。
        String voucherId = order.getVoucherId().toString();
        Long result = stringRedisTemplate.execute(RETURN_STOCK_SCRIPT,
                Arrays.asList("seckill:stock:" + voucherId,
                        "seckill:order:" + voucherId, "seckill:returned:" + voucherId),
                order.getUserId().toString(), orderId.toString());
        if (result == null || result < 0) {
            throw new IllegalStateException("Redis库存或抢券记录缺失，等待重试，订单ID=" + orderId);
        }
        // 3.标记已处理。若这一步失败，下轮仍会重试，但 Lua 不会重复加库存。
        boolean marked = lambdaUpdate()
                .set(VoucherOrder::getRedisStockReturned, true)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 4)
                .update();
        if (!marked) {
            throw new IllegalStateException("回补标记更新失败，等待重试，订单ID=" + orderId);
        }
    }
}
