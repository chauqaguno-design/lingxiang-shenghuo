package com.linxiang.controller;
import com.linxiang.dto.Result;
import com.linxiang.utils.RedisConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** 仅本机 demo profile 使用，不是生产身份认证。数据均为合成演示账号。 */
@RestController
@RequestMapping("/demo")
@ConditionalOnProperty(name="app.demo-enabled",havingValue="true")
public class DemoController {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    public DemoController(JdbcTemplate db,StringRedisTemplate redis) {this.db=db;this.redis=redis;}
    @PostMapping("/login")
    public Result login(@RequestBody Map<String,String> body) {
        String phone=body.get("phone");
        if(phone==null || !phone.matches("1[3-9][0-9]{9}")) return Result.fail("请输入 11 位演示手机号");
        db.update("INSERT IGNORE INTO tb_user(phone,nick_name) VALUES(?,?)",phone,"邻享体验官"+phone.substring(7));
        Long user=db.queryForObject("SELECT id FROM tb_user WHERE phone=?",Long.class,phone);
        String token=UUID.randomUUID().toString().replace("-","");
        Map<String,String> fields=new HashMap<>();
        fields.put("id",user.toString());fields.put("nickName","邻享体验官"+phone.substring(7));fields.put("icon","");
        redis.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,fields);
        redis.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }
    @GetMapping("/status")
    public Result status() { return Result.ok(Collections.singletonMap("demo",true)); }
}
