-- 已有数据库执行一次；新数据库直接使用 lingxiangshenghuo.sql，不要重复执行此文件。
-- 如果旧系统已有“已取消且库存已回补”的订单，启用任务前需把这些订单的新标记设为 1。
-- 本项目首次引入关单时没有这类历史订单，默认 0 即可。
ALTER TABLE tb_voucher_order
    ADD COLUMN redis_stock_returned TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '关单后Redis库存是否已回补，0否，1是' AFTER status,
    ADD INDEX idx_order_timeout (status, redis_stock_returned, create_time);
