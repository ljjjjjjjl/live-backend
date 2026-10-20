package com.live.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.common.UserStatus;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.service.PhoneLoginService;
import com.live.service.SmsCodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手机号绑定 / 换绑的安全回归测试（对应修复项 9、10 与 7）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("手机号绑定与换绑安全")
class PhoneBindSecurityTest {

    @Autowired
    private PhoneLoginService phoneLoginService;

    @Autowired
    private SmsCodeService smsCodeService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private final List<String> createdUsernames = new ArrayList<>();
    private final List<String> usedPhones = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String username : createdUsernames) {
            userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        }
        createdUsernames.clear();
        for (String phone : usedPhones) {
            redis.delete(List.of("sms:code:" + phone, "sms:attempt:" + phone,
                    "sms:resend:" + phone, "sms:daily:" + phone));
        }
        usedPhones.clear();
    }

    // ------------------------------------------------ 修复项 9：唯一性

    @Test
    @DisplayName("绑定已被别人绑定的手机号 → 拒绝")
    void bind_phoneAlreadyBoundByAnotherUser_rejected() {
        String takenPhone = newPhone();
        User owner = createUser(takenPhone);      // 手机号已被 owner 绑定
        User other = createUser(null);            // 另一个账号想抢绑

        String code = smsCodeService.send(takenPhone);

        assertThatThrownBy(() -> phoneLoginService.bind(other.getId(), takenPhone, code, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被绑定");

        // 数据库没有被改动
        assertThat(userMapper.selectById(other.getId()).getPhone()).isNull();
        assertThat(userMapper.selectById(owner.getId()).getPhone()).isEqualTo(takenPhone);
    }

    @Test
    @DisplayName("首次绑定：只带新手机号验证码即可成功")
    void bind_firstTime_succeeds() {
        User user = createUser(null);
        String phone = newPhone();
        String code = smsCodeService.send(phone);

        assertThatCode(() -> phoneLoginService.bind(user.getId(), phone, code, null))
                .doesNotThrowAnyException();
        assertThat(userMapper.selectById(user.getId()).getPhone()).isEqualTo(phone);
    }

    @Test
    @DisplayName("手机号已被占用时，验证码仍被消费（不会因为失败而留下可用验证码）")
    void bind_conflict_consumesCode() {
        String takenPhone = newPhone();
        createUser(takenPhone);
        User other = createUser(null);
        String code = smsCodeService.send(takenPhone);

        assertThatThrownBy(() -> phoneLoginService.bind(other.getId(), takenPhone, code, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> phoneLoginService.bind(other.getId(), takenPhone, code, null))
                .as("同一个验证码不能再用第二次")
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------- 修复项 10：换绑二次认证

    @Test
    @DisplayName("换绑不带旧手机号验证码 → 拒绝（防止 token 泄漏即永久失号）")
    void bind_rebindWithoutOldCode_rejected() {
        String oldPhone = newPhone();
        User user = createUser(oldPhone);
        String newPhone = newPhone();
        String newCode = smsCodeService.send(newPhone);

        assertThatThrownBy(() -> phoneLoginService.bind(user.getId(), newPhone, newCode, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原手机号");

        assertThat(userMapper.selectById(user.getId()).getPhone()).as("换绑失败不得改库").isEqualTo(oldPhone);
        assertThat(redis.hasKey("sms:code:" + newPhone))
                .as("旧号验证失败前不应消耗新号验证码").isTrue();
    }

    @Test
    @DisplayName("换绑时旧手机号验证码错误 → 拒绝")
    void bind_rebindWithWrongOldCode_rejected() {
        String oldPhone = newPhone();
        User user = createUser(oldPhone);
        String newPhone = newPhone();
        String newCode = smsCodeService.send(newPhone);
        String realOldCode = smsCodeService.send(oldPhone);

        assertThatThrownBy(() -> phoneLoginService.bind(user.getId(), newPhone, newCode, flip(realOldCode)))
                .isInstanceOf(BusinessException.class);
        assertThat(userMapper.selectById(user.getId()).getPhone()).isEqualTo(oldPhone);
    }

    @Test
    @DisplayName("换绑同时验证旧号与新号验证码 → 成功")
    void bind_rebindWithBothCodes_succeeds() {
        String oldPhone = newPhone();
        User user = createUser(oldPhone);
        String newPhone = newPhone();

        String newCode = smsCodeService.send(newPhone);
        String oldCode = smsCodeService.send(oldPhone);

        assertThatCode(() -> phoneLoginService.bind(user.getId(), newPhone, newCode, oldCode))
                .doesNotThrowAnyException();
        assertThat(userMapper.selectById(user.getId()).getPhone()).isEqualTo(newPhone);

        // 两个验证码都被消费：不能重放
        assertThat(redis.hasKey("sms:code:" + newPhone)).isFalse();
        assertThat(redis.hasKey("sms:code:" + oldPhone)).isFalse();
    }

    @Test
    @DisplayName("重复绑定同一个手机号（新号==旧号）不需要旧号验证码")
    void bind_samePhone_isTreatedAsFirstBind() {
        String phone = newPhone();
        User user = createUser(phone);
        String code = smsCodeService.send(phone);

        assertThatCode(() -> phoneLoginService.bind(user.getId(), phone, code, null))
                .doesNotThrowAnyException();
        assertThat(userMapper.selectById(user.getId()).getPhone()).isEqualTo(phone);
    }

    // ------------------------------------------------- 修复项 7：手机号不入名

    @Test
    @DisplayName("手机号自动建号：username 不包含手机号，昵称也不含手机号片段")
    void phoneLogin_generatedUsernameDoesNotContainPhone() {
        String phone = newPhone();
        String code = smsCodeService.send(phone);

        var loginVO = phoneLoginService.login(phone, code);

        assertThat(loginVO.getUsername()).doesNotContain(phone).startsWith("u_");
        assertThat(loginVO.getName()).doesNotContain(phone);
        createdUsernames.add(loginVO.getUsername());
        assertThat(loginVO.getUserId()).isNotNull();

        User created = userMapper.selectById(loginVO.getUserId());
        assertThat(created.getPhone()).isEqualTo(phone);
        assertThat(created.getStatus()).isEqualTo(UserStatus.NORMAL);
    }

    // ---------------------------------------------------------------- 工具

    /** 建一个账号；phone 传 null 表示"还没绑手机号" */
    private User createUser(String phone) {
        String username = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createdUsernames.add(username);
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("pw123456"));
        u.setName("测试");
        u.setPhone(phone);
        u.setStatus(UserStatus.NORMAL);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        if (phone != null) {
            usedPhones.add(phone);
        }
        return u;
    }

    private String newPhone() {
        String phone = "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
        usedPhones.add(phone);
        return phone;
    }

    /** 造一个"一定不等于真码"的 6 位数字（翻转最后一位） */
    private static String flip(String code) {
        char last = code.charAt(code.length() - 1);
        char flipped = last == '0' ? '1' : '0';
        return code.substring(0, code.length() - 1) + flipped;
    }
}
