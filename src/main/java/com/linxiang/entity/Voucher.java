package com.linxiang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 *
 * </p>
 *
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺id
     */
    private Long shopId;

    /**
     * 代金券标题
     */
    private String title;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 使用规则
     */
    private String rules;

    /**
     * 支付金额，单位是分：10000 表示需要支付 100 元。
     */
    private Long payValue;

    /**
     * 抵扣金额，单位是分：12000 表示可抵扣 120 元。
     */
    private Long actualValue;

    /**
     * 优惠券类型：0 为普通券，1 为秒杀券。新增秒杀券的请求应显式传入 1。
     */
    private Integer type;

    /**
     * 优惠券状态：1 为上架，店铺优惠券查询只返回此状态的记录。
     */
    private Integer status;
    /**
     * 秒杀库存，实际存放在 tb_seckill_voucher 中。
     * exist=false 表示它不是 tb_voucher 的列，但仍可以接收请求参数和承载关联查询结果。
     */
    @TableField(exist = false)
    private Integer stock;

    /**
     * 秒杀开始时间，实际存放在 tb_seckill_voucher 中，由新增秒杀券请求传入。
     */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 秒杀结束时间，实际存放在 tb_seckill_voucher 中，由新增秒杀券请求传入。
     */
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
