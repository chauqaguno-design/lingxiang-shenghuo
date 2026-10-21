package com.lingxiangshenghuo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.lingxiangshenghuo.mapper")
@EnableRetry
@EnableRabbit
@EnableScheduling
@SpringBootApplication
public class LingxiangshenghuoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LingxiangshenghuoApplication.class, args);
    }

}
