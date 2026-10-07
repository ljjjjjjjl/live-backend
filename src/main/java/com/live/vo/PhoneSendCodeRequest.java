package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送短信验证码 入参。
 *
 * <p>只含手机号。手机号格式：11 位、1 开头（与 User.phone 的校验规则一致）。
 */
@Data
public class PhoneSendCodeRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式错误")
    private String phone;
}
