package com.linxiang.limiter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 登录用户维度限流；key 使用 SpEL，例如 #p0 代表方法的第一个参数（优惠券 id）。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String business();
    String key() default "#p0";
    int maxRequests() default 5;
    int windowSeconds() default 10;
}
