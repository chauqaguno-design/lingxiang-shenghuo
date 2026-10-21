package com.linxiang.utils;

import com.linxiang.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** 验证登录拦截方向和线程复用时的用户信息清理，不启动 Web 服务。 */
class LoginInterceptorTest {

    private final LoginInterceptor interceptor = new LoginInterceptor();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void cleanCurrentUser() {
        UserHolder.removeUser();
    }

    @Test
    void loggedInUserCanReachProtectedEndpoint() {
        UserDTO user = new UserDTO();
        user.setId(17L);
        UserHolder.saveUser(user);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        assertSame(user, UserHolder.getUser());
    }

    @Test
    void anonymousUserGetsUnauthorizedInsteadOfReachingController() {
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void completionClearsUserEvenWhenControllerFailed() {
        UserDTO user = new UserDTO();
        user.setId(17L);
        UserHolder.saveUser(user);

        interceptor.afterCompletion(request, response, new Object(),
                new IllegalStateException("模拟业务异常"));

        assertNull(UserHolder.getUser());
        // 同一个测试线程再接匿名请求，不能沿用上一次请求的用户身份。
        assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
