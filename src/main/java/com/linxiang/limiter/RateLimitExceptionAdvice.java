package com.linxiang.limiter;

import com.linxiang.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitExceptionAdvice {
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handle(RateLimitException exception) {
        if (exception.getCause() != null) {
            log.error("抢券入口限流服务异常", exception);
        }
        return ResponseEntity.status(exception.getStatus()).body(Result.fail(exception.getMessage()));
    }
}
