package com.live.controller;

import com.live.common.Result;
import com.live.service.PhoneLoginService;
import com.live.vo.LoginVO;
import com.live.vo.PhoneLoginRequest;
import com.live.vo.PhoneSendCodeRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机号 + 短信验证码 登录接口（与账号密码登录共存，第 3 周扩展）。
 *
 * <ul>
 *   <li>POST /api/auth/sms/send    —— 发送验证码（Mock 发送，公开）</li>
 *   <li>POST /api/auth/sms/login   —— 手机号 + 验证码 登录（公开，登录即注册，返回 JWT）</li>
 *   <li>POST /api/auth/phone/bind  —— 给已登录账号绑定/补填手机号（需 JWT）</li>
 * </ul>
 *
 * <p><b>与现有登录共存</b>：本 Controller 是**新增**，不改 {@code UserController}。
 * 手机号登录成功后同样返回 {@link LoginVO}（token + 用户信息），前端拿 token 后走同一套鉴权。
 *
 * <p><b>鉴权路径</b>：{@code /api/auth/sms/**} 是公开的（发码/登录不需要 token）；
 * {@code /api/auth/phone/**} 需要 token（见 WebConfig 拦截配置），这样 bind 才能取到 userId。
 */
@RestController
@RequestMapping("/api/auth")
public class PhoneAuthController {

    @Autowired
    private PhoneLoginService phoneLoginService;

    /**
     * 是否在响应里回显 Mock 验证码（仅开发用）。
     * 配置项：app.sms.expose-mock-code（默认 true 方便本地测试）。
     * ⚠️ 生产环境把它设为 false —— 否则任何人都能从响应里直接拿到验证码。
     */
    @Value("${app.sms.expose-mock-code:true}")
    private boolean exposeMockCode;

    /**
     * 发送短信验证码。
     * Body: {"phone":"13812345678"}
     *
     * 开发阶段（app.sms.expose-mock-code=true）会把验证码放在 data.mockCode 里方便测试；
     * 生产环境请设为 false，只返回发送成功。
     */
    @PostMapping("/sms/send")
    public Result<Map<String, String>> sendCode(@Valid @RequestBody PhoneSendCodeRequest req) {
        String code = phoneLoginService.sendCode(req.getPhone());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("phone", req.getPhone());
        if (exposeMockCode) {
            data.put("mockCode", code);   // 仅开发环境回显
        }
        return Result.ok(data);
    }

    /**
     * 手机号 + 验证码 登录（登录即注册）。
     * Body: {"phone":"13812345678","code":"123456"}
     * 返回：data = { token, userId, username, name }（与账号密码登录结构一致）
     */
    @PostMapping("/sms/login")
    public Result<LoginVO> smsLogin(@Valid @RequestBody PhoneLoginRequest req) {
        return Result.ok(phoneLoginService.login(req.getPhone(), req.getCode()));
    }

    /**
     * 绑定手机号到当前登录账号（补填手机号）。
     * Body: {"phone":"13812345678","code":"123456"}
     * 需要请求头 Authorization: Bearer <token>；userId 由 JwtInterceptor 放进 request。
     */
    @PostMapping("/phone/bind")
    public Result<Void> bindPhone(@Valid @RequestBody PhoneLoginRequest req,
                                  @RequestAttribute("userId") Long userId) {
        phoneLoginService.bind(userId, req.getPhone(), req.getCode());
        return Result.ok(null);
    }
}
