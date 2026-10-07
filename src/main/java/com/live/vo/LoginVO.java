package com.live.vo;

import lombok.Data;

/**
 * 登录成功返回体：Token + 用户基本信息。
 *
 * 为什么登录返回 Token 而不是直接返回 User？
 * 登录成功后前端要拿 Token 存起来，之后每个请求带上它（Authorization: Bearer <token>）
 * 才能证明身份。所以 data 里除了用户信息，还要有 token。
 */
@Data
public class LoginVO {

    /** JWT Token，前端要存起来，之后请求带上 */
    private String token;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String name;
}
