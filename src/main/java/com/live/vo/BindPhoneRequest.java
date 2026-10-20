package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 绑定 / 换绑手机号入参。
 *
 * <h3>为什么多了一个 {@code oldCode}</h3>
 * 旧实现只校验"新手机号的验证码"，也就是说：**只要拿到登录 token 就能把账号手机号换成任意号码**。
 * 一旦 token 泄漏（XSS、日志、共享设备），攻击者换绑后受害者就**永久失去账号**（手机号成了别人的）。
 *
 * <p>所以换绑要求**两个验证码**：
 * <ul>
 *   <li>{@code code} —— 发到**新手机号**的验证码，证明"新号码是我的"；</li>
 *   <li>{@code oldCode} —— 发到**旧手机号**的验证码，证明"旧号码也是我的"，即二次认证。</li>
 * </ul>
 * 首次绑定（账号还没有手机号）时不需要 {@code oldCode}；换绑时缺失就直接拒绝，绝不放行。
 *
 * <p>兼容性：请求体是"新增可选字段"，前端老版本只传 {@code {phone, code}} 仍然合法，
 * 但只在**首次绑定**场景下能成功；换绑场景会被服务端拒绝（这正是修复目标）。
 */
@Data
public class BindPhoneRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 8, message = "验证码格式错误")
    private String code;

    /** 换绑时必填：发到**旧手机号**的验证码（二次认证） */
    @Size(max = 8, message = "验证码格式错误")
    private String oldCode;
}
