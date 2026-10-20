package com.live.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信相关配置（{@code app.sms.*}），**默认值全部取"生产安全"的那一侧**。
 *
 * <p>为什么要把 {@code @Value} 换成配置类：
 * <ol>
 *   <li>散落的 {@code @Value("${...:default}")} 无法统一校验，默认值容易写成"方便开发"的那种；</li>
 *   <li>安全开关（mock / 回显验证码）需要在一处集中校验，并在启动时做"生产禁用"检查；</li>
 *   <li>限流参数（有效期/重发间隔/次数上限/每日上限）集中一处，测试可直接注入覆盖。</li>
 * </ol>
 *
 * <p><b>生产安全默认值</b>：
 * <ul>
 *   <li>{@code mock-enabled = false} —— 不装配 Mock 实现，绝不"假装发送成功"；</li>
 *   <li>{@code expose-mock-code = false} —— 响应里绝不回显验证码。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

    /**
     * 是否启用 Mock 短信发送（只应出现在 dev/test）。
     * 默认 <b>false</b>：生产环境不装配 {@code MockSmsSender}。
     */
    private boolean mockEnabled = false;

    /**
     * 是否在 {@code /api/auth/sms/send} 响应里回显验证码。
     * 默认 <b>false</b>；且启动校验会禁止在非 dev/test profile 下打开。
     */
    private boolean exposeMockCode = false;

    /** 验证码有效期（分钟） */
    private int codeExpireMinutes = 5;

    /** 同一手机号两次发送之间的最小间隔（秒）——发送频率限制 */
    private int resendIntervalSeconds = 60;

    /** 同一手机号每日发送上限（防短信轰炸 / 刷短信费用） */
    private int dailySendLimit = 10;

    /** 同一验证码允许的最大连续校验失败次数，超过即作废该验证码 */
    private int maxVerifyAttempts = 5;

    /** 校验失败计数的窗口（分钟），与验证码有效期同量级即可 */
    private int verifyAttemptWindowMinutes = 5;
}
