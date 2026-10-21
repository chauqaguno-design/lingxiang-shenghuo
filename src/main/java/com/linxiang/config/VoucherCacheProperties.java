package com.linxiang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 券展示缓存配置。三种模式使用相同接口、相同响应，便于 JMeter 对照测试。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.voucher-cache")
public class VoucherCacheProperties {
    /** mysql：直接查库；redis：只用 Redis；caffeine：先查本机，再查 Redis。 */
    private String mode = "caffeine";
    private long capacity = 1000;
    private long localTtlSeconds = 5;
    private long redisTtlSeconds = 60;
    private long emptyTtlSeconds = 5;
}
