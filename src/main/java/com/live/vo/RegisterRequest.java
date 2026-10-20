package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册入参（**专用 DTO，不再用 User 实体接参**）。
 *
 * <h3>为什么必须换成 DTO（批量赋值 / Mass Assignment）</h3>
 * 旧实现是 {@code register(@RequestBody User user)}，客户端可以往 JSON 里塞实体上的**任意字段**：
 * <ul>
 *   <li>{@code id} —— 指定主键（占号/覆盖语义）；</li>
 *   <li>{@code phone} —— **直接写手机号，完全绕过短信验证码校验**，可抢占他人手机号；</li>
 *   <li>{@code avatar} / {@code createTime} —— 篡改展示与时间字段；</li>
 *   <li>{@code role} / {@code status} / {@code balance} —— 当前表虽无这些列，
 *       但一旦以后加上（很常见），实体接参就会立刻变成"客户端自己给自己提权"的漏洞。</li>
 * </ul>
 * 现在只暴露允许的三个字段，其余字段即使传了也会被 Jackson 忽略，
 * 且 {@code role/status/balance} 由服务端强制赋值（见 {@code UserService#register}）。
 *
 * <p>注意：{@code phone} **故意不在这里** —— 手机号必须走
 * {@code POST /api/auth/phone/bind}（带短信验证码）绑定，注册时不能直接写。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在6~100之间")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称长度不能超过50")
    private String name;

    /** 可选头像地址：只允许 http(s) 或站内相对路径，避免 javascript: 之类的伪协议 */
    @Pattern(regexp = "^$|^(https?://|/)[\\s\\S]*$", message = "头像地址格式不合法")
    private String avatar;
}
