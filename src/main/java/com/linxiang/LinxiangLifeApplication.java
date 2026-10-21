package com.linxiang;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.linxiang.mapper")
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
// 暴露当前 Spring 代理，供 AopContext.currentProxy() 调用事务方法，避免同类直接调用导致事务失效。
@EnableAspectJAutoProxy(exposeProxy = true)
public class LinxiangLifeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinxiangLifeApplication.class, args);
    }

}
