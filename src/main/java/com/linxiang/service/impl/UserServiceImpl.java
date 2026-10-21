package com.linxiang.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linxiang.dto.LoginFormDTO;
import com.linxiang.dto.Result;
import com.linxiang.dto.UserDTO;
import com.linxiang.entity.User;
import com.linxiang.mapper.UserMapper;
import com.linxiang.service.IUserService;
import com.linxiang.utils.RedisConstants;
import com.linxiang.utils.RegexUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.nio.file.CopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号格式无效");
        }

        String code = RandomUtil.randomNumbers(6);
        //2.发送验证码
        stringRedisTemplate.opsForValue().set("login:code:"+phone, code);
        stringRedisTemplate.expire(RedisConstants.LOGIN_CODE_KEY +phone, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //3.保存验证码
        return Result.ok();

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号格式无效");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode()))
        {
            return Result.fail("验证码错误");
        }
        User user = query().eq("phone", phone).one();

        if(user == null)
        {
            user = createUserByPhone(phone);
        }
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions
                .create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    public User createUserByPhone(String phone) {
            User user = new User();
            user.setPhone(phone);
            user.setNickName("邻享用户" + phone.substring(7)+RandomUtil.randomString(4));
            save(user);
        return user;
    }
}
