package com.live.controller;

import com.live.common.Result;
import com.live.service.UserService;
import com.live.vo.LoginRequest;
import com.live.vo.LoginVO;
import com.live.vo.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：注册 / 登录
 *
 * <p><b>入参一律用 DTO</b>（{@link RegisterRequest} / {@link LoginRequest}），不再用 {@code User} 实体。
 * 用实体接参等于把持久化对象的全部字段暴露成请求契约，客户端可以塞 {@code id/phone/avatar/status/...}
 * （批量赋值漏洞）；DTO 只声明允许的字段，其余字段即使传了也会被忽略。
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
     * <p>校验在 DTO 上用标准 Jakarta Validation 注解完成（不再需要校验分组）。
     * 响应仍为统一结构 {@code {code,msg,data}}，兼容既有前端。
     */
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Long> register(@Valid @RequestBody RegisterRequest req) {
        return userService.register(req);
    }

    /**
     * 登录（成功返回 token）
     * POST /api/user/login
     * Body: {"username":"tom","password":"123456"}
     * 返回 data = { token, userId, username, name }
     */
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest req) {
        return userService.login(req);
    }
}
