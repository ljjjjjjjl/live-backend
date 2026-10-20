package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.common.PhoneMasker;
import com.live.common.UserStatus;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.util.JwtUtil;
import com.live.vo.LoginVO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 手机号登录服务（与账号密码登录共存，复用同一套 JWT）。
 *
 * <p><b>开闭原则</b>：本类为**新增**，不修改 {@code UserService.login}（账号密码登录）一行代码。
 * 两种登录方式最终都调用 {@link JwtUtil#generateToken} 签发相同的 JWT、返回相同的 {@link LoginVO}，
 * 所以下游（JwtInterceptor / 前端）完全无感——这就是"共存且不改老代码"。
 *
 * <p>三个能力：
 * <ol>
 *   <li>{@link #sendCode} 发送验证码</li>
 *   <li>{@link #login} 手机号 + 验证码登录（<b>登录即注册</b>：手机号没账号就自动建号）</li>
 *   <li>{@link #bind} 把手机号绑定到**已登录**的账号（首次绑定 / 换绑）</li>
 * </ol>
 *
 * <h3>本轮安全修复要点</h3>
 * <ol>
 *   <li><b>验证码"原子校验并消费"</b>：不再 verify + clear 两步（可重放、可残留），
 *       统一走 {@link SmsCodeService#verifyAndConsume}；</li>
 *   <li><b>手机号不再进入 username / 昵称</b>：建号用随机不可逆标识，避免手机号顺着
 *       用户名跑到日志、URL、前端展示里；</li>
 *   <li><b>登录校验账号状态</b>：封禁/注销/停用/锁定一律拒绝，且不签发 token；</li>
 *   <li><b>换绑必须做二次认证</b>：除新手机号验证码外，还要旧手机号验证码，
 *       防止"token 泄漏 = 永久失号"；</li>
 *   <li><b>日志与响应全部脱敏</b>：手机号一律经 {@link PhoneMasker#mask(String)}。</li>
 * </ol>
 */
@Slf4j
@Service
public class PhoneLoginService {

    /** 审计日志通道（绑定/换绑/登录拒绝等敏感操作） */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.live.audit");

    /** 手机号格式：11 位、1 开头 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * ① 发送验证码。
     *
     * @param phone 手机号
     * @return 验证码（**仅供受控的 dev 回显路径使用**；生产环境调用方不会把它放进响应）
     */
    public String sendCode(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException("手机号格式错误");
        }
        return smsCodeService.send(phone);
    }

    /**
     * ② 手机号 + 验证码登录（登录即注册）。
     *
     * <p>处理逻辑（顺序很重要）：
     * <ol>
     *   <li>校验手机号格式；</li>
     *   <li><b>原子消费验证码</b>（{@code verifyAndConsume}）：失败即抛异常，成功即删除。
     *       —— 放在最前面，这样后续任何步骤抛异常都不会留下"可复用"的验证码；</li>
     *   <li>按 phone 查 user 表：
     *     <ul>
     *       <li><b>查到</b> → 老用户：先做**账号状态校验**（封禁等一律拒绝），再用其 userId；</li>
     *       <li><b>查不到</b> → 新手机号，**自动创建账号**（username 用随机不可逆标识，
     *           昵称用随机后缀，phone 写入表，密码置为随机串的 BCrypt 密文——保证该账号无法用密码登录）。</li>
     *     </ul>
     *   </li>
     *   <li>复用 JwtUtil 签发 JWT，返回与账号密码登录一致的 LoginVO。</li>
     * </ol>
     *
     * <p><b>为什么不再用 {@code @Transactional}</b>：① 这里最多写一条 insert，本来不需要事务；
     * ② "Redis 调用不应被包进数据库事务"是本项目的既定原则；③ 更关键的是——并发建号时
     * 捕获唯一键冲突后要**重查**，如果包在 RR 事务里，重查走的是事务快照，
     * 看不到另一个事务刚提交的行，兜底逻辑会失效。去掉事务后每条语句各自提交，重查才能看到。
     */
    public LoginVO login(String phone, String code) {
        // 1. 手机号格式
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException("手机号格式错误");
        }
        // 2. 原子校验并消费验证码（失败不删除；成功只可能有一次）
        smsCodeService.verifyAndConsume(phone, code);

        // 3. 按手机号查用户；查不到就"登录即注册"
        User user = findByPhone(phone);
        if (user == null) {
            try {
                user = createUserByPhone(phone);
                log.info("手机号 {} 首次登录，已自动创建账号 userId={}",
                        PhoneMasker.mask(phone), user.getId());
            } catch (DataIntegrityViolationException e) {
                // 并发兜底：两个请求同时用同一新手机号登录，另一个已抢先建号
                // → 唯一索引(uk_phone / uk_username)拦下，这里重查一次即可正常登录
                user = findByPhone(phone);
                if (user == null) {
                    throw new BusinessException("登录失败，请稍后重试");
                }
            }
        }

        // 4. 账号状态校验：封禁/注销/停用/锁定都不允许登录（不签发 token）
        if (!UserStatus.canLogin(user.getStatus())) {
            AUDIT.warn("event=PHONE_LOGIN_REJECTED userId={} phone={} reason=STATUS_{}",
                    user.getId(), PhoneMasker.mask(phone), user.getStatus());
            throw new BusinessException("账号当前不可用，请联系管理员");
        }

        // 5. 签发 JWT（与账号密码登录同一套工具，返回结构一致）
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        return vo;
    }

    /**
     * ③ 绑定 / 换绑手机号（给已登录账号补填或更换手机号）。
     *
     * <h3>两条硬性规则</h3>
     * <ol>
     *   <li><b>唯一性</b>：该手机号已被**别的账号**绑定 → 拒绝（应用层预检 + 数据库
     *       {@code uk_phone} 唯一索引兜底 + 捕获唯一键冲突）。</li>
     *   <li><b>换绑必须二次认证</b>：如果当前账号**已经有手机号**且与新号不同，
     *       必须同时提交发到**旧手机号**的验证码 {@code oldCode}。
     *       只凭登录 token 就能换绑 = token 一泄漏账号就永久易主，这是典型的账号劫持入口。</li>
     * </ol>
     *
     * <p>首次绑定（账号原本没有手机号）只需要新号验证码，保持与旧版前端兼容。
     *
     * @param userId  当前登录用户 id（来自 Token）
     * @param phone   要绑定的（新）手机号
     * @param code    发到**新手机号**的验证码
     * @param oldCode 发到**旧手机号**的验证码；仅换绑时必填
     */
    public void bind(Long userId, String phone, String code, String oldCode) {
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException("手机号格式错误");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        String currentPhone = user.getPhone();
        boolean isRebind = currentPhone != null && !currentPhone.isEmpty()
                && !currentPhone.equals(phone);

        // ① ⭐ 唯一性预检要放在"消费验证码"之前。
        //    原来的顺序是「先消费旧码 → 再消费新码 → 最后才查手机号是否已被占用」，
        //    于是撞上"该手机号已被绑定"时，**两个验证码都已经被吃掉**，
        //    用户得重新收两次短信才能再试一次 —— 纯粹的体验伤害，且白白消耗短信配额。
        //    预检只是友好提示，真正的并发防线仍是 uk_phone 唯一索引（见第 ⑤ 步）。
        User exist = findByPhone(phone);
        if (exist != null && !exist.getId().equals(userId)) {
            AUDIT.warn("event=PHONE_BIND_REJECTED userId={} phone={} reason=ALREADY_BOUND_BY_{}",
                    userId, PhoneMasker.mask(phone), exist.getId());
            throw new BusinessException("该手机号已被绑定");
        }

        if (isRebind) {
            // ② 换绑：先校验旧手机号验证码（二次认证），再校验新手机号验证码。
            //    顺序上把"旧号"放前面：旧号验证不过就不消耗新号那次验证码。
            if (oldCode == null || oldCode.trim().isEmpty()) {
                AUDIT.warn("event=PHONE_REBIND_REJECTED userId={} reason=MISSING_OLD_CODE oldPhone={} newPhone={}",
                        userId, PhoneMasker.mask(currentPhone), PhoneMasker.mask(phone));
                throw new BusinessException("换绑手机号需要验证原手机号，请先获取原手机号验证码");
            }
            smsCodeService.verifyAndConsume(currentPhone, oldCode);
        }

        // ③ 校验新手机号验证码（原子消费）
        smsCodeService.verifyAndConsume(phone, code);

        // ④ 落库：真正的并发防线是 uk_phone 唯一索引
        user.setPhone(phone);
        // 状态不允许被这条路径改动（updateById 会带上 status，这里显式保持原值，避免脏数据）
        user.setStatus(user.getStatus() == null ? UserStatus.NORMAL : user.getStatus());
        try {
            userMapper.updateById(user);
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：两个用户同时绑同一手机号，唯一索引只放行一个
            AUDIT.warn("event=PHONE_BIND_REJECTED userId={} phone={} reason=DUPLICATE_KEY",
                    userId, PhoneMasker.mask(phone));
            throw new BusinessException("该手机号已被绑定");
        }

        // ⑤ 审计日志（脱敏）：谁、什么时候、首次绑定还是换绑
        AUDIT.info("event={} userId={} oldPhone={} newPhone={}",
                isRebind ? "PHONE_REBIND" : "PHONE_BIND",
                userId,
                PhoneMasker.mask(currentPhone),
                PhoneMasker.mask(phone));
        log.info("用户 {} {}手机号 {}", userId, isRebind ? "换绑" : "绑定", PhoneMasker.mask(phone));
    }

    /** 按手机号查用户（用 limit 1 兜底，避免历史脏数据导致 TooManyResults） */
    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)
                .last("limit 1"));
    }

    /**
     * 手机号首次登录时自动建号。
     *
     * <h3>为什么 username / 昵称都用随机值，而不是手机号</h3>
     * 旧实现是 {@code username = "phone_" + phone}、{@code name = "用户" + 手机号后 4 位}。
     * 用户名会出现在**日志、URL、接口响应、前端页面**里，等于把手机号（PII）复制到了
     * 五六个地方，脱敏一处没用。现在改成：
     * <ul>
     *   <li>username = {@code u_} + 16 位随机 hex（UUID 派生，**不可反推手机号**，仍满足 uk_username 唯一）；</li>
     *   <li>昵称 = "用户" + 4 位随机数字（不透露手机号任何片段）；</li>
     *   <li>手机号只存在 {@code user.phone} 这一个字段里（库内明文，出库即脱敏）。</li>
     * </ul>
     * 密码同样用随机串的 BCrypt 密文，保证该账号**只能走验证码登录**，不能用密码登录。
     */
    private User createUserByPhone(String phone) {
        User user = new User();
        user.setUsername("u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        user.setName("用户" + String.format(Locale.ROOT, "%04d", SECURE_RANDOM.nextInt(10000)));
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 随机密码，登录不可用
        user.setStatus(UserStatus.NORMAL);                                       // 服务端强制普通状态
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }
}
