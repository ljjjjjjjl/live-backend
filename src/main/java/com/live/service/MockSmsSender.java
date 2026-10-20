package com.live.service;

import com.live.common.PhoneMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 短信发送的 Mock 实现：**不真调第三方**，只把验证码打到日志，直接返回成功。
 *
 * <h3>与旧实现的区别（安全修复点）</h3>
 * <ul>
 *   <li><b>去掉 {@code @Primary}</b>：原来它标了 {@code @Primary} 且无条件装配，
 *       只要它在 classpath 上就**永远优先**于真实实现 —— 生产环境一旦忘了排除它，
 *       所有短信都会"静默不发但返回成功"，用户收不到码，系统却以为发成功。</li>
 *   <li><b>改成条件装配</b>：只有 {@code app.sms.mock-enabled=true} 时才注册这个 Bean。
 *       该项默认 false，且只在 dev/test profile 里被打开（见
 *       {@code application-dev.yml} / 测试用 {@code application-test.yml}）。</li>
 *   <li><b>日志脱敏</b>：手机号先过 {@link PhoneMasker#mask(String)}（验证码本身仍打印，
 *       否则 Mock 阶段没法调试，这也是它只能存在于 dev/test 的原因）。</li>
 * </ul>
 *
 * <p>接真实短信（阿里云/腾讯云）时：新增一个 {@code AliyunSmsSender implements SmsSender}
 * 的无条件 {@code @Service}，业务代码一行不改；此时生产环境
 * {@code mock-enabled=false} → 只会装配真实实现。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "mock-enabled", havingValue = "true")
public class MockSmsSender implements SmsSender {

    @Override
    public boolean send(String phone, String code) {
        // ⚠️ 仅用于 dev/test：真实项目绝不会把验证码打进日志
        log.warn("【Mock 短信·仅 dev/test】给 {} 发送验证码：{}（未真实发送，模拟成功）",
                PhoneMasker.mask(phone), code);
        return true;
    }
}
