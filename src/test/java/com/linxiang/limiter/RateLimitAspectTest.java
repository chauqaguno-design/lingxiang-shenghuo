package com.linxiang.limiter;

import com.linxiang.dto.Result;
import com.linxiang.dto.UserDTO;
import com.linxiang.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitAspectTest {
    private SlidingWindowRateLimiter limiter;
    private TestEndpoint target;
    private TestEndpoint proxy;

    @BeforeEach
    void setUp() {
        limiter = mock(SlidingWindowRateLimiter.class);
        target = new TestEndpoint();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new RateLimitAspect(limiter));
        proxy = factory.getProxy();
        UserDTO user = new UserDTO();
        user.setId(71L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void allowedRequestUsesAuthenticatedUserAndVoucherAsKey() {
        when(limiter.allow("limit:voucher-seckill:71:9", 5, 10)).thenReturn(true);

        assertTrue(proxy.purchase(9L).getSuccess());

        assertEquals(1, target.purchases);
        verify(limiter).allow("limit:voucher-seckill:71:9", 5, 10);
    }

    @Test
    void exceededWindowReturns429AndDoesNotReachOrderCode() {
        when(limiter.allow(anyString(), anyInt(), anyInt())).thenReturn(false);

        RateLimitException exception = assertThrows(RateLimitException.class, () -> proxy.purchase(9L));
        ResponseEntity<Result> response = new RateLimitExceptionAdvice().handle(exception);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertFalse(response.getBody().getSuccess());
        assertEquals(0, target.purchases);
    }

    @Test
    void redisFailureIs503AndCannotSilentlyBypassRateLimit() {
        when(limiter.allow(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("Redis offline"));

        RateLimitException exception = assertThrows(RateLimitException.class, () -> proxy.purchase(9L));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals(0, target.purchases);
    }

    @Test
    void anonymousRequestIs401AndNeverTouchesOrderOrLimiter() {
        UserHolder.removeUser();
        RateLimitException exception = assertThrows(RateLimitException.class, () -> proxy.purchase(9L));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(limiter);
        assertEquals(0, target.purchases);
    }

    @Test
    void differentUsersDoNotShareOneWindow() {
        when(limiter.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        proxy.purchase(9L);
        UserDTO other = new UserDTO();
        other.setId(72L);
        UserHolder.saveUser(other);
        proxy.purchase(9L);

        verify(limiter).allow("limit:voucher-seckill:71:9", 5, 10);
        verify(limiter).allow("limit:voucher-seckill:72:9", 5, 10);
    }

    public static class TestEndpoint {
        int purchases;

        @RateLimit(business = "voucher-seckill", key = "#p0", maxRequests = 5, windowSeconds = 10)
        public Result purchase(Long voucherId) {
            purchases++;
            return Result.ok(voucherId);
        }
    }
}
