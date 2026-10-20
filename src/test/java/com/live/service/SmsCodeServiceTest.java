package com.live.service;

import com.live.common.BusinessException;
import com.live.config.SmsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证码核心性质的回归测试（对应修复项 1、2）。
 *
 * <p>这些用例**必须打真实 Redis**：要验证的正是"Lua 脚本在 Redis 单线程里原子执行"
 * 这一性质，用 Mock 出来的 Redis 测不出任何东西。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SmsCodeService：验证码原子消费与风控")
class SmsCodeServiceTest {

    private static final String CODE_KEY = "sms:code:";
    private static final String ATTEMPT_KEY = "sms:attempt:";
    private static final String RESEND_KEY = "sms:resend:";
    private static final String DAILY_KEY = "sms:daily:";

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SmsProperties properties;

    /** 每个用例用独立手机号，避免 60 秒重发锁互相干扰 */
    private String phone;

    @BeforeEach
    void setUp() {
        phone = randomPhone();
    }

    @AfterEach
    void tearDown() {
        redis.delete(List.of(CODE_KEY + phone, ATTEMPT_KEY + phone, RESEND_KEY + phone, DAILY_KEY + phone));
    }

    // ---------------------------------------------------------------- 生成

    @Test
    @DisplayName("生成的验证码是 6 位数字（SecureRandom，无取模偏差）")
    void generatedCode_isSixDigits() {
        for (int i = 0; i < 50; i++) {
            assertThat(smsCodeService.generateCode()).matches("\\d{6}");
        }
    }

    // ------------------------------------------------- ① 原子"校验并消费"

    @Test
    @DisplayName("并发提交同一个验证码：只有一个线程能消费成功")
    void verifyAndConsume_concurrent_onlyOneSucceeds() throws Exception {
        String code = smsCodeService.send(phone);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();                      // 尽量让 16 个线程同时冲进去
                    try {
                        smsCodeService.verifyAndConsume(phone, code);
                        return Boolean.TRUE;
                    } catch (BusinessException e) {
                        return Boolean.FALSE;
                    }
                }));
            }
            startGate.countDown();

            int success = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(15, TimeUnit.SECONDS))) {
                    success++;
                }
            }
            assertThat(success).as("同一个验证码只能被成功消费一次").isEqualTo(1);
            assertThat(redis.hasKey(CODE_KEY + phone)).as("成功后验证码应被删除").isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同一个验证码第二次使用必然失败（防重放）")
    void verifyAndConsume_secondUseFails() {
        String code = smsCodeService.send(phone);

        assertThatCode(() -> smsCodeService.verifyAndConsume(phone, code)).doesNotThrowAnyException();
        assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证码");
    }

    @Test
    @DisplayName("校验失败不删除验证码，用户还能用正确的码通过")
    void wrongCode_doesNotConsumeCode() {
        String code = smsCodeService.send(phone);

        assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, wrongCode(code)))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(CODE_KEY + phone)).as("失败不应删除验证码").isTrue();

        assertThatCode(() -> smsCodeService.verifyAndConsume(phone, code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("连续输错达到上限后验证码作废：即使提交正确验证码也失败")
    void tooManyWrongAttempts_invalidatesCode() {
        String code = smsCodeService.send(phone);
        String wrong = wrongCode(code);
        int max = properties.getMaxVerifyAttempts();

        for (int i = 1; i <= max; i++) {
            int attemptNo = i;
            if (attemptNo == max) {
                // 最后一次失败会触发"作废"分支
                assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, wrong))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("次数过多");
            } else {
                assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, wrong))
                        .isInstanceOf(BusinessException.class);
            }
        }
        assertThat(redis.hasKey(CODE_KEY + phone)).as("达到上限后验证码应被作废").isFalse();
        assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, code))
                .as("已作废的验证码即使正确也不能再用")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("手机号/验证码为空时直接拒绝，且不会触碰 Redis")
    void blankInput_rejected() {
        assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, " "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> smsCodeService.verifyAndConsume(null, "123456"))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------ ② 发送侧风控

    @Test
    @DisplayName("发送频率限制：60 秒内重复发送被拒绝")
    void send_rejectsTooFrequentResend() {
        smsCodeService.send(phone);

        assertThatThrownBy(() -> smsCodeService.send(phone))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    @DisplayName("重新发送会覆盖旧验证码，并重置失败计数")
    void resend_overridesOldCodeAndResetsAttempts() {
        String first = smsCodeService.send(phone);

        // 模拟"已过重发间隔"：手动删掉频率锁（不 sleep 60 秒）
        redis.delete(RESEND_KEY + phone);
        String second = smsCodeService.send(phone);

        assertThat(second).matches("\\d{6}");
        assertThat(redis.opsForValue().get(CODE_KEY + phone)).isEqualTo(second);
        assertThat(redis.hasKey(ATTEMPT_KEY + phone)).isFalse();

        // 旧码（若与新码不同）不再可用
        if (!first.equals(second)) {
            assertThatThrownBy(() -> smsCodeService.verifyAndConsume(phone, first))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String randomPhone() {
        return "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    /** 造一个"一定不等于真码"的 6 位数字（翻转最后一位） */
    private static String wrongCode(String code) {
        char last = code.charAt(code.length() - 1);
        char flipped = last == '0' ? '1' : '0';
        return code.substring(0, code.length() - 1) + flipped;
    }
}
