package com.linxiang.controller;


import com.linxiang.dto.Result;
import com.linxiang.entity.Voucher;
import com.linxiang.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;



    /**
     * 新增普通券：POST /voucher，只写入优惠券基本信息表。
     * 金额单位为分，例如支付 100 元时 payValue 传 10000；普通券 type 传 0。
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        // id 由数据库自增，save 后会回填到 voucher，供响应中返回给前端。
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券：POST /voucher/seckill。
     * 请求应携带 type=1，以及 stock、beginTime、endTime；Service 在同一数据库事务中保存两张表。
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        // Controller 只接收 JSON 并调用业务方法，不在这里拆分两张表的写入流程。
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的上架优惠券列表：GET /voucher/list/{shopId}。
     * 普通券与秒杀券一起返回；秒杀券额外包含库存、开始时间与结束时间。
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
