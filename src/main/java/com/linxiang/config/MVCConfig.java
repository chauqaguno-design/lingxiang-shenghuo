package com.linxiang.config;
import com.linxiang.utils.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.*;
@Configuration
public class MVCConfig implements WebMvcConfigurer {
    private final StringRedisTemplate redis;
    public MVCConfig(StringRedisTemplate redis) {this.redis=redis;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(redis)).addPathPatterns("/**").order(1);
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
                String path=req.getRequestURI(),method=req.getMethod();
                // 当前交付的是用户端；旧课程的管理写接口没有角色系统，默认关闭。
                // 按实际匹配到的控制器判断，避免编码路径或分号参数绕过字符串前缀检查。
                if(handler instanceof org.springframework.web.method.HandlerMethod) {
                    Class<?> controller=((org.springframework.web.method.HandlerMethod)handler).getBeanType();
                    boolean management=com.linxiang.controller.ShopController.class.isAssignableFrom(controller)
                            || com.linxiang.controller.VoucherController.class.isAssignableFrom(controller);
                    if((management && !"GET".equals(method)) || com.linxiang.controller.UploadController.class.isAssignableFrom(controller)) {
                        res.setStatus(403);return false;
                    }
                }
                if(!"GET".equals(method) && (path.startsWith("/shop/") || path.equals("/shop") ||
                        path.startsWith("/voucher/") || path.equals("/voucher") || path.startsWith("/upload"))) {
                    res.setStatus(403);return false;
                }
                boolean publicRead="GET".equals(method) && (path.equals("/") || path.equals("/index.html") ||
                        path.startsWith("/assets/") || path.startsWith("/js/") || path.startsWith("/css/") ||
                        path.startsWith("/imgs/") || path.equals("/favicon.ico") || path.startsWith("/shop/") ||
                        path.startsWith("/shop-type/") || path.startsWith("/voucher/list/") || path.equals("/demo/status"));
                boolean login=path.equals("/demo/login") || path.equals("/user/login") || path.equals("/user/code") || path.equals("/error");
                if(publicRead || login || UserHolder.getUser()!=null) return true;
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
                try {res.getWriter().write("{\"success\":false,\"errorMsg\":\"请先登录\"}");} catch(java.io.IOException ignored) {}
                return false;
            }
        }).addPathPatterns("/**").order(2);
    }
}
