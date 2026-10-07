package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.util.JwtUtil;
import com.live.vo.LoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 *   <li>{@link #bind} 把手机号绑定到**已登录**的账号（补填手机号）</li>
 * </ol>
 */
@Slf4j
@Service
public class PhoneLoginService {

    /** 手机号格式：11 位、1 开头 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

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
     * @return 验证码（Mock 阶段返回，便于测试；真实项目应返回 null / 只返回成功）
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
     * <p>处理逻辑：
     * <ol>
     *   <li>校验手机号格式；</li>
     *   <li>校验验证码（Redis 比对），失败抛 {@link BusinessException}；</li>
     *   <li>按 phone 查 user 表：
     *     <ul>
     *       <li><b>查到</b> → 老用户，直接用其 userId；</li>
     *       <li><b>查不到</b> → 新手机号，**自动创建账号**（username 用 phone_手机号，name 用"用户+尾号"，
     *           phone 写入表，密码置为随机串的 BCrypt 密文——保证该账号无法用密码登录）。</li>
     *     </ul>
     *   </li>
     *   <li>验证码用后即焚；</li>
     *   <li>复用 JwtUtil 签发 JWT，返回与账号密码登录一致的 LoginVO。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(String phone, String code) {
        // 1. 手机号格式
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException("手机号格式错误");
        }
        // 2. 验证码校验
        if (!smsCodeService.verify(phone, code)) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 3. 按手机号查用户；查不到就"登录即注册"
        User user = findByPhone(phone);
        if (user == null) {
            try {
                user = createUserByPhone(phone);
                log.info("手机号 {} 首次登录，已自动创建账号 userId={}", phone, user.getId());
            } catch (DuplicateKeyException e) {
                // 并发兜底：两个请求同时用同一新手机号登录，另一个已抢先建号
                // → 唯一索引(uk_phone/uk_username)拦下，这里重查一次即可正常登录
                user = findByPhone(phone);
                if (user == null) {
                    throw new BusinessException("登录失败，请稍后重试");
                }
            }
        }

        // 4. 验证码用后即焚（防重放）
        smsCodeService.clear(phone);

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
     * ③ 绑定手机号到当前登录账号（给"已有账号但注册时没填手机号"的用户补填）。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>从 JWT 取当前 userId（Controller 传入）；</li>
     *   <li>校验手机号格式 + 验证码；</li>
     *   <li>先查这个手机号是否**已被别的账号绑定** → 是则报错（保证一号只绑一个账号）；</li>
     *   <li>把 phone 写进当前 user 记录（updateById）。</li>
     * </ol>
     *
     * @param userId 当前登录用户 id（来自 Token）
     * @param phone  要绑定的手机号
     * @param code   短信验证码
     */
    @Transactional(rollbackFor = Exception.class)
    public void bind(Long userId, String phone, String code) {
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException("手机号格式错误");
        }
        if (!smsCodeService.verify(phone, code)) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 手机号是否已被"别人"绑定
        User exist = findByPhone(phone);
        if (exist != null && !exist.getId().equals(userId)) {
            throw new BusinessException("该手机号已被绑定");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setPhone(phone);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个用户同时绑同一手机号，唯一索引 uk_phone 只放行一个
            throw new BusinessException("该手机号已被绑定");
        }

        smsCodeService.clear(phone);
        log.info("用户 {} 绑定手机号 {}", userId, phone);
    }

    /** 按手机号查用户（用 limit 1 兜底，避免历史脏数据导致 TooManyResults） */
    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)
                .last("limit 1"));
    }

    /**
     * 手机号首次登录时自动建号。
     * 密码不设明文/空串，而是随机串的 BCrypt 密文 —— 保证该账号只能走手机号验证码登录，
     * 不能用密码登录（否则空密码或猜到的密码会命中）。
     */
    private User createUserByPhone(String phone) {
        User user = new User();
        user.setUsername("phone_" + phone);                       // 唯一，满足 uk_username
        user.setName("用户" + phone.substring(phone.length() - 4)); // 用尾号生成昵称
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 随机密码，登录不可用
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }
}
