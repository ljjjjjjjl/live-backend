package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账号密码登录入参（专用 DTO）。
 *
 * <p>登录只需要"用户名 + 密码"两个字段；用实体接参同样给了客户端多余的赋值面
 * （虽然登录路径不落库，但保持"入参即契约"的一致性更不容易出错，
 * 例如以后改成 {@code updateById(user)} 就会立刻出问题）。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 100, message = "密码长度不能超过100")
    private String password;
}
