package com.live.security;

import com.live.common.BusinessException;
import com.live.service.SmsCodeService;
import com.live.service.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 修复项 3 的关键验收：**生产 profile（mock 关闭）下 MockSmsSender 不加载**。
 *
 * <p>用 {@code properties} 覆盖成 {@code app.sms.mock-enabled=false}（等价于生产默认值），
 * 断言容器里**没有任何** {@link SmsSender} Bean；并进一步断言此时"发验证码"是
 * **直接失败**而不是"静默返回成功"——后者才是最危险的故障模式。
 */
@SpringBootTest(properties = "app.sms.mock-enabled=false")
@ActiveProfiles("test")
@DisplayName("Mock 短信关闭时：不加载 Mock，且发送会失败而不是静默成功")
class SmsMockDisabledContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("app.sms.mock-enabled=false：容器里没有 SmsSender 实现")
    void mockSenderNotLoadedWhenDisabled() {
        assertThat(context.getBeansOfType(SmsSender.class)).isEmpty();
    }

    @Test
    @DisplayName("没有可用发送实现时，发码接口抛业务异常（绝不静默返回成功）")
    void sendFailsLoudlyWithoutSender() {
        String phone = "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        try {
            assertThatThrownBy(() -> smsCodeService.send(phone))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("短信服务");
            // 失败时必须不留下可用验证码
            assertThat(redis.hasKey("sms:code:" + phone)).isFalse();
        } finally {
            redis.delete(List.of("sms:code:" + phone, "sms:attempt:" + phone,
                    "sms:resend:" + phone, "sms:daily:" + phone));
        }
    }
}
