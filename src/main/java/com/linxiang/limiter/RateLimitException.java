package com.linxiang.limiter;

import org.springframework.http.HttpStatus;

/** 携带 HTTP 状态，避免把限流、Redis 故障伪装成成功响应。 */
public class RateLimitException extends RuntimeException {
    private final HttpStatus status;

    public RateLimitException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public RateLimitException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
