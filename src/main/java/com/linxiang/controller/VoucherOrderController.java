package com.linxiang.controller;
import com.linxiang.dto.Result;
import com.linxiang.limiter.RateLimit;
import com.linxiang.order.OrderLifecycle;
import com.linxiang.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    private final OrderLifecycle orders;
    private final boolean demo;
    public VoucherOrderController(OrderLifecycle orders,@Value("${app.demo-enabled:false}") boolean demo) {
        this.orders=orders;this.demo=demo;
    }
    /** 返回订单号仅表示请求已受理，需要查询 result 确认落单。 */
    @PostMapping("/seckill/{id}")
    @RateLimit(business="voucher-seckill",key="#p0",maxRequests=5,windowSeconds=10)
    public Result seckill(@PathVariable("id") Long id,
                          @RequestHeader(value="X-Request-Id",required=false) String requestId) {
        return orders.submit(UserHolder.getUser().getId(),id,requestId);
    }
    @GetMapping("/result/{id}")
    public Result result(@PathVariable("id") Long id) { return orders.result(id,UserHolder.getUser().getId()); }
    @GetMapping("/my")
    public Result mine() { return orders.mine(UserHolder.getUser().getId()); }
    @PostMapping("/{id}/pay")
    public Result pay(@PathVariable("id") Long id) {
        return demo?orders.pay(id,UserHolder.getUser().getId()):Result.fail("模拟支付仅在 demo 模式开放");
    }
}
