package com.live.service;

import com.live.common.BusinessException;
import com.live.common.PhoneMasker;
import com.live.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务：生成 → 存 Redis(带 TTL) → **原子"校验并消费"**。
 *
 * <p><b>为什么用 Redis 而不是 session</b>：项目用 JWT 是**无状态**的，服务端不存 session；
 * 验证码放 Redis 既天然带过期(TTL)，又能被多实例共享（水平扩展时任意实例都能校验）。
 *
 * <h3>Key 设计</h3>
 * <ul>
 *   <li>{@code sms:code:{phone}} —— 验证码本体，TTL = {@code app.sms.code-expire-minutes}（默认 5 分钟）</li>
 *   <li>{@code sms:attempt:{phone}} —— 连续校验失败计数，TTL = {@code app.sms.verify-attempt-window-minutes}</li>
 *   <li>{@code sms:resend:{phone}} —— 发送频率锁，TTL = {@code app.sms.resend-interval-seconds}（默认 60 秒）</li>
 *   <li>{@code sms:daily:{phone}} —— 当日发送计数，TTL 到当天 24:00</li>
 * </ul>
 *
 * <h3>为什么"校验"和"删除"必须合成一次原子操作</h3>
 * 旧实现是 {@code verify()} + {@code clear()} 两步：
 * <ol>
 *   <li><b>可重放</b>：两个并发请求可以同时 {@code GET} 到同一个码 → 都通过校验 → 同一个码被用两次；</li>
 *   <li><b>可残留</b>：两步之间抛异常（比如登录建号失败），{@code clear()} 没执行到 → 验证码留在 Redis 里继续可用；</li>
 *   <li>多一次 RTT。</li>
 * </ol>
 * 现在改成一段 **Lua 脚本**（Redis 单线程执行，天然原子）：
 * <pre>
 *   GET 验证码
 *     不存在           -> -1（不删任何东西，也绝不通过）
 *     相等             -> 删验证码 + 删失败计数 -> 1   ← 只有这一个分支会"消费"成功
 *     不相等           -> 失败计数 +1；达到上限则删掉验证码 -> -2；否则 -> 0（**不删验证码**，允许用户重试）
 * </pre>
 * 由此得到三条不变量：
 * <ul>
 *   <li><b>同一个验证码只可能成功消费一次</b>（并发下也只有一个线程拿到返回值 1）；</li>
 *   <li><b>校验失败不删除验证码</b>（用户输错了还能再输，不会被"一次输错就作废"）；</li>
 *   <li>业务后续步骤失败时，验证码**已经被消费**，不会被重放（失败关闭，fail-closed）。</li>
 * </ul>
 *
 * <p><b>生成算法</b>：改用 {@link SecureRandom}（旧实现用 {@code ThreadLocalRandom}，
 * 是可预测的伪随机 —— 攻击者观察几个验证码就能推算后续序列）。取值范围 000000~999999，
 * 用 {@code nextInt(1_000_000)} 做拒绝采样，无取模偏差；不足 6 位左补 0。
 */
@Slf4j
@Service
public class SmsCodeService {

    private static final String CODE_KEY_PREFIX = "sms:code:";
    private static final String ATTEMPT_KEY_PREFIX = "sms:attempt:";
    private static final String RESEND_KEY_PREFIX = "sms:resend:";
    private static final String DAILY_KEY_PREFIX = "sms:daily:";

    /** 密码学安全随机源：全局一个即可（SecureRandom 本身线程安全） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 校验结果：消费成功 */
    private static final long R_CONSUMED = 1L;
    /** 校验结果：验证码错误（计数未超限，验证码保留） */
    private static final long R_MISMATCH = 0L;
    /** 校验结果：验证码不存在/已过期/已被消费 */
    private static final long R_ABSENT = -1L;
    /** 校验结果：失败次数超限，验证码已作废 */
    private static final long R_TOO_MANY_ATTEMPTS = -2L;

    /**
     * 原子"校验并消费"脚本。
     *
     * <pre>
     * KEYS[1] = 验证码 key      KEYS[2] = 失败计数 key
     * ARGV[1] = 用户提交的验证码  ARGV[2] = 失败计数窗口(秒)   ARGV[3] = 最大失败次数
     * </pre>
     */
    private static final DefaultRedisScript<Long> VERIFY_AND_CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local saved = redis.call('GET', KEYS[1])
            if not saved then
                return -1
            end
            if saved == ARGV[1] then
                redis.call('DEL', KEYS[1])
                redis.call('DEL', KEYS[2])
                return 1
            end
            local attempts = redis.call('INCR', KEYS[2])
            if attempts == 1 then
                redis.call('EXPIRE', KEYS[2], ARGV[2])
            end
            if attempts >= tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1])
                return -2
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectProvider<SmsSender> smsSenders;
    private final SmsProperties properties;

    public SmsCodeService(StringRedisTemplate stringRedisTemplate,
                          ObjectProvider<SmsSender> smsSenders,
                          SmsProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.smsSenders = smsSenders;
        this.properties = properties;
    }

    /**
     * 生成并发送验证码。
     *
     * <p>顺序：**先占频率锁 → 再计入当日配额 → 生成并存 → 发送 → 发送失败则回滚**。
     * 任何一步失败都不会留下"可用的验证码"。
     *
     * @param phone 手机号（已通过格式校验）
     * @return 本次明文验证码（**只允许在 service 内部/受控的 dev 回显路径使用**，不要写日志）
     * @throws BusinessException 触发频率限制、超出每日配额、短信服务不可用或发送失败
     */
    public String send(String phone) {
        long expireMinutes = properties.getCodeExpireMinutes();
        // 频率锁 TTL 至少 1 秒：Redis 的 SET ... EX 0 会直接报错（invalid expire time），
        // 这里做防御，避免有人把 resend-interval-seconds 配成 0 导致发码接口 500
        long resendSeconds = Math.max(1L, properties.getResendIntervalSeconds());

        // ① 发送频率限制：SETNX 成功才有资格发（原子，天然防并发重复发送）
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                RESEND_KEY_PREFIX + phone, "1", resendSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException("发送过于频繁，请 " + resendSeconds + " 秒后再试");
        }

        // ② 当日发送配额（防短信轰炸/刷费用）
        String dailyKey = DAILY_KEY_PREFIX + phone;
        Long daily = stringRedisTemplate.opsForValue().increment(dailyKey);
        if (daily != null && daily == 1L) {
            // 首次计数时把 TTL 设到当天 24:00（秒级）
            long secondsToMidnight = Duration.between(LocalDateTime.now(),
                    LocalDateTime.of(LocalDateTime.now().toLocalDate(), LocalTime.MAX)).getSeconds() + 1;
            stringRedisTemplate.expire(dailyKey, secondsToMidnight, TimeUnit.SECONDS);
        }
        if (daily != null && daily > properties.getDailySendLimit()) {
            // ⚠️ 本轮修复：这里原来直接抛异常，既不释放频率锁、也不回退当日计数，后果是
            //   ① 用户被"发送过于频繁"和"今日已达上限"两个限制叠加锁死；
            //   ② daily 计数只增不减，每次被拒还会 +1（越试越糟）。
            //   现在把多记的那 1 次减回去，并释放频率锁，让提示语和真实状态一致。
            stringRedisTemplate.opsForValue().decrement(dailyKey);
            stringRedisTemplate.delete(RESEND_KEY_PREFIX + phone);
            throw new BusinessException("今日验证码发送次数已达上限，请明天再试");
        }

        // ③ 生成（SecureRandom）并落库到 Redis
        String code = generateCode();
        stringRedisTemplate.opsForValue()
                .set(CODE_KEY_PREFIX + phone, code, expireMinutes, TimeUnit.MINUTES);
        // 新码重置失败计数，避免上一个码的失败次数拖累新码
        stringRedisTemplate.delete(ATTEMPT_KEY_PREFIX + phone);

        // ④ 发送：找不到实现 / 抛异常 / 返回 false 都算失败 → 回滚刚写入的验证码与频率锁
        SmsSender sender = smsSenders.getIfAvailable();
        if (sender == null) {
            rollbackSend(phone);
            log.error("短信发送失败：未配置任何 SmsSender 实现 phone={}", PhoneMasker.mask(phone));
            throw new BusinessException("短信服务暂不可用，请稍后再试");
        }
        try {
            boolean ok = sender.send(phone, code);
            if (!ok) {
                rollbackSend(phone);
                throw new BusinessException("短信发送失败，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            rollbackSend(phone);
            log.error("短信发送异常 phone={}", PhoneMasker.mask(phone), e);
            throw new BusinessException("短信发送失败，请稍后再试");
        }

        log.info("验证码已下发 phone={} 有效期={}分钟", PhoneMasker.mask(phone), expireMinutes);
        return code;
    }

    /**
     * **原子校验并消费**验证码：成功即删除，失败不删除（失败次数超限才作废）。
     *
     * <p>调用方**必须**用这个方法，不要再出现"先 verify 再 clear"的两步写法。
     *
     * @param phone 手机号
     * @param code  用户提交的验证码
     * @throws BusinessException 校验失败（提示语统一，不区分"码错"还是"码不存在"，避免探测）
     */
    public void verifyAndConsume(String phone, String code) {
        if (phone == null || code == null || code.trim().isEmpty()) {
            throw new BusinessException("验证码错误或已过期");
        }
        Long result = stringRedisTemplate.execute(
                VERIFY_AND_CONSUME_SCRIPT,
                List.of(CODE_KEY_PREFIX + phone, ATTEMPT_KEY_PREFIX + phone),
                code.trim(),
                String.valueOf(Duration.ofMinutes(properties.getVerifyAttemptWindowMinutes()).getSeconds()),
                String.valueOf(properties.getMaxVerifyAttempts()));

        if (result != null && result == R_CONSUMED) {
            return; // 已消费（同一验证码不可能再有第二次）
        }
        long r = result == null ? R_ABSENT : result;
        if (r == R_TOO_MANY_ATTEMPTS) {
            log.warn("验证码失败次数超限已作废 phone={}", PhoneMasker.mask(phone));
            throw new BusinessException("验证码错误次数过多，请重新获取");
        }
        // r == 0（码错）或 -1（不存在/过期/已被用掉）：统一提示，不泄露具体原因
        throw new BusinessException("验证码错误或已过期");
    }

    /**
     * 生成 6 位数字验证码（SecureRandom + 拒绝采样，无取模偏差）。
     *
     * <p>⚠️ 一定要带上 {@link Locale#ROOT}：{@code String.format("%06d", n)} 默认用
     * **系统 Locale**，在阿拉伯语等区域会输出本地数字（٠١٢٣٤٥），
     * 这样下发的验证码用户用 ASCII 键盘根本输不出来（或者反过来校验永远匹配不上）。
     */
    String generateCode() {
        return String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /** 发送失败时回滚：删掉验证码，并释放频率锁让用户可以立即重试 */
    private void rollbackSend(String phone) {
        stringRedisTemplate.delete(CODE_KEY_PREFIX + phone);
        stringRedisTemplate.delete(ATTEMPT_KEY_PREFIX + phone);
        stringRedisTemplate.delete(RESEND_KEY_PREFIX + phone);
    }
}
