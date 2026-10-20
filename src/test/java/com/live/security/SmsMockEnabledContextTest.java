package com.live.security;

import com.live.service.MockSmsSender;
import com.live.service.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修复项 3 的验收：**Mock 短信实现是否被装配，只取决于 app.sms.mock-enabled**。
 *
 * <p>旧实现给 {@code MockSmsSender} 标了 {@code @Primary} 且无条件装配，
 * 于是它在任何 profile 下都会抢占真实实现 —— 生产会"短信没发出去但接口返回成功"。
 * 现在它带 {@code @ConditionalOnProperty}，默认不加载。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Mock 短信在 test profile 下按开关加载")
class SmsMockEnabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("app.sms.mock-enabled=true：装配 MockSmsSender")
    void mockSenderLoadedWhenEnabled() {
        assertThat(context.getBeansOfType(SmsSender.class)).hasSize(1);
        assertThat(context.getBean(SmsSender.class)).isInstanceOf(MockSmsSender.class);
    }
}
