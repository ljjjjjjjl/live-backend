package com.live.security;

import com.live.config.SmsConfigValidator;
import com.live.config.SmsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 短信安全开关的**启动期校验**测试（对应修复项 4）。
 *
 * <p>不启动 Spring 上下文：直接构造 {@link SmsConfigValidator}（第三个参数传 null，
 * 因为"回显开关"的校验完全不依赖 SmsSender 容器），验证"什么配置必须让应用起不来"。
 */
@DisplayName("SmsConfigValidator：生产环境误开回显必须启动失败")
class SmsConfigValidatorTest {

    @Test
    @DisplayName("非 dev/test profile 打开 expose-mock-code → 抛异常（启动失败）")
    void exposeMockCode_outsideDevTest_failsStartup() {
        SmsConfigValidator validator = new SmsConfigValidator(props(true), env("prod"), null);

        assertThatThrownBy(validator::checkExposeMockCode)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expose-mock-code");
    }

    @Test
    @DisplayName("没有任何 profile（默认=生产姿态）打开 expose-mock-code → 同样启动失败")
    void exposeMockCode_withoutProfile_failsStartup() {
        SmsConfigValidator validator = new SmsConfigValidator(props(true), env(), null);

        assertThatThrownBy(validator::checkExposeMockCode).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("dev / test profile 打开 expose-mock-code → 允许（本地调试）")
    void exposeMockCode_inDevTest_allowed() {
        assertThatCode(() -> new SmsConfigValidator(props(true), env("dev"), null)
                .checkExposeMockCode()).doesNotThrowAnyException();
        assertThatCode(() -> new SmsConfigValidator(props(true), env("test"), null)
                .checkExposeMockCode()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("expose-mock-code=false（默认）在任何 profile 下都允许")
    void exposeMockCodeDisabled_alwaysAllowed() {
        assertThatCode(() -> new SmsConfigValidator(props(false), env("prod"), null)
                .checkExposeMockCode()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("纯函数判定：大小写与多 profile 组合都按白名单处理")
    void isExposeMockCodeAllowed_matrix() {
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(true, new String[]{"DEV"})).isTrue();
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(true, new String[]{"prod", "test"})).isTrue();
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(true, new String[]{"prod"})).isFalse();
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(true, new String[0])).isFalse();
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(true, null)).isFalse();
        assertThat(SmsConfigValidator.isExposeMockCodeAllowed(false, new String[]{"prod"})).isTrue();
    }

    private static SmsProperties props(boolean exposeMockCode) {
        SmsProperties p = new SmsProperties();
        p.setExposeMockCode(exposeMockCode);
        return p;
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
