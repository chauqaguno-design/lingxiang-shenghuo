package com.linxiang.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 对需要登录的接口做身份检查；token 解析和续期由前置的 RefreshTokenInterceptor 负责。
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // RefreshTokenInterceptor 已尝试根据 token 恢复用户；仍为空才说明未登录或登录已失效。
        // 这里必须判断 == null：判断反了会拒绝已登录用户，却让未登录请求进入秒杀业务。
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求线程会被复用，结束后清理身份信息，避免污染该线程处理的下一次请求。
        UserHolder.removeUser();
    }
}
