package com.live.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.common.UserStatus;
import com.live.entity.User;
import com.live.mapper.UserMapper;
import com.live.service.UserService;
import com.live.vo.LoginRequest;
import com.live.vo.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册 / 登录的安全回归测试（对应修复项 5、6、8 与数据库唯一约束验收）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("用户注册与登录安全")
class UserSecurityTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    // ---------------------------------------------------- 修复项 5：批量赋值

    @Test
    @DisplayName("注册时传 id/phone/avatar/status/role/balance 等字段，全部被忽略")
    void register_ignoresSensitiveFields() throws Exception {
        String username = uniqueUsername();
        String payload = """
                {
                  "username": "%s",
                  "password": "pw123456",
                  "name": "张三",
                  "id": 999999,
                  "phone": "13800000000",
                  "createTime": "2000-01-01T00:00:00",
                  "role": "ADMIN",
                  "status": "BANNED",
                  "balance": 999999
                }
                """.formatted(username);

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        User saved = findByUsername(username);
        assertThat(saved).as("用户应已创建").isNotNull();
        assertThat(saved.getId()).as("主键由数据库生成，不接受客户端注入").isNotEqualTo(999999L);
        assertThat(saved.getPhone()).as("手机号必须走验证码绑定，注册不能直写").isNull();
        assertThat(saved.getStatus()).as("状态由服务端强制为 NORMAL").isEqualTo(UserStatus.NORMAL);
        assertThat(saved.getCreateTime()).as("创建时间由服务端生成")
                .isAfter(LocalDateTime.now().minusMinutes(5));
        assertThat(saved.getPassword()).as("密码必须 BCrypt 加密存储").startsWith("$2");
    }

    @Test
    @DisplayName("注册入参非法头像协议（javascript:）被拒绝")
    void register_rejectsNonHttpAvatar() throws Exception {
        String payload = """
                {"username":"%s","password":"pw123456","name":"头像","avatar":"javascript:alert(1)"}
                """.formatted(uniqueUsername());

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                // 参数校验失败现在返回 HTTP 400（GlobalExceptionHandler 改造后，
                // 4xx/5xx 不再一律伪装成 200 —— 否则网关和 APM 里根本看不到错误率）
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1));
    }

    // ------------------------------------------- 修复项 6：并发唯一键冲突

    @Test
    @DisplayName("并发注册同名用户：只有一个成功，其余是业务冲突（不是 500/系统繁忙）")
    void register_duplicateUsername_concurrently_oneSucceeds() throws Exception {
        String username = uniqueUsername();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    RegisterRequest req = new RegisterRequest();
                    req.setUsername(username);
                    req.setPassword("pw123456");
                    req.setName("并发");
                    try {
                        userService.register(req);
                        return "OK";
                    } catch (BusinessException e) {
                        return "BIZ:" + e.getMessage();
                    } catch (Exception e) {
                        // 关键断言：不允许把数据库异常原样抛给调用方
                        return "RAW:" + e.getClass().getSimpleName();
                    }
                }));
            }
            startGate.countDown();

            int ok = 0;
            for (Future<String> f : futures) {
                String result = f.get(20, TimeUnit.SECONDS);
                assertThat(result).as("并发冲突必须被翻译成业务异常").doesNotStartWith("RAW:");
                if ("OK".equals(result)) {
                    ok++;
                }
            }
            assertThat(ok).as("同名用户只能成功注册一个").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("重复用户名顺序注册：返回业务提示而不是系统繁忙")
    void register_duplicateUsername_sequentially_reportsConflict() throws Exception {
        String username = uniqueUsername();
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"pw123456","name":"重复"}
                                """.formatted(username)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"pw123456","name":"重复"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }

    // ------------------------------------------------- 修复项 8：封禁用户

    @Test
    @DisplayName("封禁用户登录被拒绝（不签发 token）")
    void login_bannedUser_rejected() {
        String username = insertUser("BANNED");
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword("pw123456");

        assertThatThrownBy(() -> userService.login(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    @DisplayName("注销/停用/锁定用户同样被拒绝")
    void login_otherDisabledStatuses_rejected() {
        for (String st : List.of(UserStatus.DELETED, UserStatus.DISABLED, UserStatus.LOCKED)) {
            String username = insertUser(st);
            LoginRequest req = new LoginRequest();
            req.setUsername(username);
            req.setPassword("pw123456");
            assertThatThrownBy(() -> userService.login(req))
                    .as("状态 %s 不应允许登录", st)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("正常状态用户可以登录并拿到 token")
    void login_normalUser_succeeds() {
        String username = insertUser(UserStatus.NORMAL);
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword("pw123456");

        assertThat(userService.login(req).getData().getToken()).isNotBlank();
    }

    // ------------------------------------------ 修复项 4：响应不含 mockCode

    @Test
    @DisplayName("expose-mock-code=false：响应只含脱敏手机号，且完全没有 mockCode 字段")
    void sendCode_responseHasNoMockCode() throws Exception {
        String phone = randomPhone();
        usedPhones.add(phone);
        String masked = phone.substring(0, 3) + "****" + phone.substring(7);

        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.phoneMasked").value(masked))
                .andExpect(jsonPath("$.data.mockCode").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    // ------------------------------------------- 验收：数据库唯一索引存在

    @Test
    @DisplayName("user 表存在 username / phone 的唯一索引（并发防线）")
    void schema_hasUniqueIndexes() {
        Integer uniqueIndexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' "
                        + "AND NON_UNIQUE = 0 AND INDEX_NAME IN ('uk_username','uk_phone')",
                Integer.class);
        assertThat(uniqueIndexCount).as("必须有 uk_username 与 uk_phone 两个唯一索引").isEqualTo(2);
    }

    @Test
    @DisplayName("user 表存在 status 列且默认 NORMAL（封禁能力的前提）")
    void schema_hasStatusColumn() {
        String defaultValue = jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'status'",
                String.class);
        assertThat(defaultValue).isEqualTo("NORMAL");
    }

    // ---------------------------------------------------------------- 工具

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    /** 直接建一个指定状态的用户（绕过接口，模拟数据库里已存在的用户） */
    private String insertUser(String status) {
        String username = uniqueUsername();
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("pw123456"));
        u.setName("测试");
        u.setStatus(status);
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        return username;
    }

    private String uniqueUsername() {
        String username = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createdUsernames.add(username);
        return username;
    }

    private static String randomPhone() {
        return "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }
}
