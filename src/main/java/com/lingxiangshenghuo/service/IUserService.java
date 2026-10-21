package com.lingxiangshenghuo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingxiangshenghuo.dto.LoginFormDTO;
import com.lingxiangshenghuo.dto.Result;
import com.lingxiangshenghuo.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
