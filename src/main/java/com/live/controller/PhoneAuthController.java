package com.live.controller;

import com.live.common.PhoneMasker;
import com.live.common.Result;
import com.live.config.SmsProperties;
import com.live.service.PhoneLoginService;
import com.live.vo.BindPhoneRequest;
import com.live.vo.LoginVO;
import com.live.vo.PhoneLoginRequest;
import com.live.vo.PhoneSendCodeRequest;
import com.live.vo.SendCodeResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 手机号 + 短信验证码 登录接口（与账号密码登录共存，第 3 周扩展）。
 *
 * <ul>
 *   <li>POST /api/auth/sms/send    —— 发送验证码（Mock/真实由 app.sms.mock-enabled 决定，公开）</li>
 *   <li>POST /api/auth/sms/login   —— 手机号 + 验证码 登录（公开，登录即注册，返回 JWT）</li>
 *   <li>POST /api/auth/phone/bind  —— 给已登录账号绑定/换绑手机号（需 JWT）</li>
 * </ul>
 *
 * <h3>本轮安全修复要点</h3>
 * <ol>
 *   <li><b>不再用 {@code @Value} 读开关</b>，改用 {@link SmsProperties}（默认 false）；
 *       且 {@code SmsConfigValidator} 会在非 dev/test profile 打开回显时**直接让应用启动失败**。</li>
 *   <li><b>响应体换成 {@link SendCodeResponse}</b>：
 *       手机号**只返回脱敏值**，{@code mockCode} 字段在生产环境**不会出现在 JSON 里**
 *       （{@code @JsonInclude(NON_NULL)}）。旧实现返回 Map 并塞完整手机号，
 *       一旦回显开关被误开，任意手机号都能直接登录。</li>
 *   <li>换绑手机号需要旧手机号验证码，见 {@link BindPhoneRequest#getOldCode()}。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth")
public class PhoneAuthController {

    @Autowired
    private PhoneLoginService phoneLoginService;

    @Autowired
    private SmsProperties smsProperties;

    /**
     * 发送短信验证码。
     * Body: {"phone":"13812345678"}
     *
     * <p>响应 data = {phoneMasked:"138****5678", expireSeconds:300}；
     * **仅** dev/test 且 {@code app.sms.expose-mock-code=true} 时才会多一个 {@code mockCode} 字段。
     */
    @PostMapping("/sms/send")
    public Result<SendCodeResponse> sendCode(@Valid @RequestBody PhoneSendCodeRequest req) {
        String code = phoneLoginService.sendCode(req.getPhone());
        SendCodeResponse data = new SendCodeResponse(
                PhoneMasker.mask(req.getPhone()),
                (int) TimeUnit.MINUTES.toSeconds(smsProperties.getCodeExpireMinutes()));
        if (smsProperties.isExposeMockCode()) {
            data.setMockCode(code);   // 仅 dev/test 回显；生产该字段不存在
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
     * 绑定 / 换绑手机号到当前登录账号。
     * Body: {"phone":"13812345678","code":"123456"}                  —— 首次绑定
     *       {"phone":"13900000000","code":"123456","oldCode":"654321"} —— 换绑（oldCode 必填）
     * 需要请求头 Authorization: Bearer <token>；userId 由 JwtInterceptor 放进 request。
     */
    @PostMapping("/phone/bind")
    public Result<Void> bindPhone(@Valid @RequestBody BindPhoneRequest req,
                                  @RequestAttribute("userId") Long userId) {
        phoneLoginService.bind(userId, req.getPhone(), req.getCode(), req.getOldCode());
        return Result.ok(null);
    }
}
