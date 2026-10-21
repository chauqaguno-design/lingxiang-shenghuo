package com.linxiang.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private String port;
    // 冒号后为空表示：未配置该属性时使用空字符串，兼容本地无密码的 Redis。
    @Value("${spring.redis.password:}")
    private String password;

    /**
     * 创建Redisson配置对象，然后交给IOC管理
     *
     * @return
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        // 获取Redisson配置对象
        Config config = new Config();
        // 添加 Redis 单节点地址；集群模式对应 config.useClusterServers()。
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + this.host + ":" + this.port);
        // 有密码才配置认证；空密码不调用 setPassword，避免对无密码 Redis 发送 AUTH。
        if (StringUtils.hasText(this.password)) {
            serverConfig.setPassword(this.password);
        }
        // 创建 RedissonClient 并交给 IOC 管理，应用关闭时调用 shutdown 释放连接。
        return Redisson.create(config);
    }
}
