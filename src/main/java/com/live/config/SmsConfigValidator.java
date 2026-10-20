package com.live.config;

import com.live.service.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * 短信配置的**启动期安全校验**：把"配置写错了"这件事挡在启动阶段，而不是等线上被人薅。
 *
 * <p>两条规则：
 * <ol>
 *   <li><b>硬失败</b>：{@code app.sms.expose-mock-code=true} 但当前不是 dev/test profile
 *       → 直接抛异常终止启动。理由：它等于把任意手机号的登录凭证公开在接口响应里，
 *       属于"必须在启动时就炸掉"的配置错误，不能只打日志（没人看日志就等于没防）。</li>
 *   <li><b>强告警</b>：{@code mock-enabled=false}（生产默认）且容器里**没有**任何真实
 *       {@link SmsSender} 实现 → 打 ERROR 级日志。此时发码接口会在调用时**直接失败**
 *       （{@code SmsCodeService} 不吞异常、不返回成功），所以不会出现
 *       "短信没发出去但接口返回成功"的静默故障。</li>
 * </ol>
 */
@Slf4j
@Component
public class SmsConfigValidator implements ApplicationRunner {

    /** 允许打开"回显验证码 / Mock 短信"的 profile 白名单 */
    private static final String[] DEV_PROFILES = {"dev", "test", "local"};

    private final SmsProperties properties;
    private final Environment environment;
    private final ObjectProvider<SmsSender> smsSenders;

    public SmsConfigValidator(SmsProperties properties,
                              Environment environment,
                              ObjectProvider<SmsSender> smsSenders) {
        this.properties = properties;
        this.environment = environment;
        this.smsSenders = smsSenders;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkExposeMockCode();
        checkRealSenderPresent();
    }

    /**
     * 校验"回显验证码"开关：非 dev/test 打开则**启动失败**。
     *
     * @throws IllegalStateException 配置在非开发环境打开
     */
    public void checkExposeMockCode() {
        if (!properties.isExposeMockCode()) {
            return;
        }
        if (!isExposeMockCodeAllowed(true, environment.getActiveProfiles())) {
            throw new IllegalStateException(
                    "安全配置错误：app.sms.expose-mock-code=true 只允许在 dev/test profile 下使用"
                            + "（当前 profile=" + Arrays.toString(environment.getActiveProfiles()) + "）。"
                            + "它会把短信验证码直接回显给调用方，等于任意手机号可登录。"
                            + "请设为 false，或显式使用 --spring.profiles.active=dev 本地调试。");
        }
        log.warn("app.sms.expose-mock-code=true：接口会回显验证码，仅限本地开发调试使用");
    }

    /**
     * 纯函数版判定（便于单测，不需要起 Spring 上下文）：
     * 开关关闭时永远允许；开关打开时只允许 dev/test/local profile。
     *
     * @param exposeMockCode 是否要求回显验证码
     * @param activeProfiles 当前激活的 profile
     * @return true 表示配置可接受
     */
    public static boolean isExposeMockCodeAllowed(boolean exposeMockCode, String[] activeProfiles) {
        if (!exposeMockCode) {
            return true;
        }
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .filter(p -> p != null)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(p -> Arrays.asList(DEV_PROFILES).contains(p));
    }

    /** 未装配任何 SmsSender 实现时强告警（发送时会失败，不会静默成功） */
    public void checkRealSenderPresent() {
        if (properties.isMockEnabled()) {
            log.warn("app.sms.mock-enabled=true：正在使用 Mock 短信实现（验证码不真实发送），仅限 dev/test");
            return;
        }
        if (smsSenders.getIfAvailable() == null) {
            log.error("未检测到任何 SmsSender 实现（app.sms.mock-enabled=false 且无真实短信实现）："
                    + "发送验证码接口会直接返回失败，绝不会静默'发送成功'。"
                    + "生产请提供真实短信实现；本地调试请加 --spring.profiles.active=dev");
        }
    }

    private boolean isDevLikeProfile() {
        return isExposeMockCodeAllowed(true, environment.getActiveProfiles());
    }
}
