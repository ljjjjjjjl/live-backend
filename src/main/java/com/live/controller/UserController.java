package com.live.controller;

import com.live.common.Result;
import com.live.common.ValidationGroups;
import com.live.entity.User;
import com.live.service.UserService;
import com.live.vo.LoginVO;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;

/**
 * 用户接口：注册 / 登录
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 注册
     * POST /api/user/register
     * Body: {"username":"tom","password":"123456","name":"Tom"}
     *
     * 校验：@Validated({Create, Default}) —— 同时校验 Create 组(姓名必填) + 默认组(username/password 非空/长度)。
     */
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Long> register(@Validated({ValidationGroups.Create.class, Default.class}) @RequestBody User user) {
        return userService.register(user);
    }

    /**
     * 登录（成功返回 token）
     * POST /api/user/login
     * Body: {"username":"tom","password":"123456"}
     * 返回 data = { token, userId, username, name }
     *
     * 校验：@Valid —— 走默认组，只校验 username/password 非空 + 长度（不校验 name/phone）。
     */
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<LoginVO> login(@Valid @RequestBody User user) {
        return userService.login(user.getUsername(), user.getPassword());
    }
}
