package com.linxiang.limiter;

import com.linxiang.dto.UserDTO;
import com.linxiang.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 只在 HTTP 入口使用 UserHolder，后台消费线程不依赖登录线程的 ThreadLocal。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitAspect {
    private final SlidingWindowRateLimiter limiter;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public RateLimitAspect(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Around(value = "@annotation(limit)", argNames = "invocation,limit")
    public Object check(ProceedingJoinPoint invocation, RateLimit limit) throws Throwable {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new RateLimitException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        MethodSignature signature = (MethodSignature) invocation.getSignature();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                invocation.getTarget(), signature.getMethod(), invocation.getArgs(), parameterNames);
        Object businessId = parser.parseExpression(limit.key()).getValue(context);
        if (businessId == null) {
            throw new IllegalArgumentException("限流业务标识不能为空");
        }
        String key = "limit:" + limit.business() + ":" + user.getId() + ":" + businessId;
        final boolean allowed;
        try {
            allowed = limiter.allow(key, limit.maxRequests(), limit.windowSeconds());
        } catch (RuntimeException exception) {
            // Redis 异常时明确失败，不能 catch 后直接进入下单，否则保护机制会悄悄失效。
            throw new RateLimitException(HttpStatus.SERVICE_UNAVAILABLE, "请求保护服务暂不可用，请稍后重试", exception);
        }
        if (!allowed) {
            throw new RateLimitException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
        }
        return invocation.proceed();
    }
}
