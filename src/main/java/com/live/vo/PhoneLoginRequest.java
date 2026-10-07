package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 手机号登录 / 绑定手机号 入参：手机号 + 短信验证码。
 */
@Data
public class PhoneLoginRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    private String code;
}
