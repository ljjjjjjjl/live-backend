package com.live.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务：生成 → 存 Redis(带过期) → 校验。
 *
 * <p><b>为什么用 Redis 而不是 session</b>：项目用 JWT 是**无状态**的，服务端不存 session；
 * 验证码放 Redis 既天然带过期(TTL)，又能被多实例共享（水平扩展时任意实例都能校验）。
 *
 * <p>Key 设计：{@code sms:code:{phone}}，值为 6 位数字，过期 5 分钟。
 * 发送时**覆盖**旧值（同一手机号只有最新一个码有效）；校验通过后**立即删除**（防重放）。
 */
@Service
public class SmsCodeService {

    private static final String KEY_PREFIX = "sms:code:";
    private static final long EXPIRE_MINUTES = 5;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SmsSender smsSender;

    /**
     * 生成验证码、存 Redis、并（Mock）发送。
     *
     * @param phone 手机号
     * @return 本次生成的验证码（Mock 阶段返回给调用方便于测试；真实项目不要返回给前端）
     */
    public String send(String phone) {
        // 6 位随机数字（100000~999999）
        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + phone, code, EXPIRE_MINUTES, TimeUnit.MINUTES);
        smsSender.send(phone, code);
        return code;
    }

    /**
     * 校验验证码是否正确（存在且相等）。
     */
    public boolean verify(String phone, String code) {
        if (phone == null || code == null || code.isEmpty()) {
            return false;
        }
        String saved = stringRedisTemplate.opsForValue().get(KEY_PREFIX + phone);
        return code.equals(saved);
    }

    /**
     * 用后即焚：校验通过后删除验证码，避免同一个码被重复使用（防重放）。
     */
    public void clear(String phone) {
        stringRedisTemplate.delete(KEY_PREFIX + phone);
    }
}
