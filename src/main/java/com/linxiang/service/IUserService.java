package com.linxiang.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.linxiang.dto.LoginFormDTO;
import com.linxiang.dto.Result;
import com.linxiang.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
