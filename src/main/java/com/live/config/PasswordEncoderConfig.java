package com.live.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密配置：注册一个 BCryptPasswordEncoder Bean。
 *
 * 为什么单独注册成 Bean 而不是在 Service 里 new？
 *   1) 加密强度（strength）等参数只在一处配置，以后要调整只改这里；
 *   2) Service 通过 @Autowired 注入，符合 Spring 依赖注入风格，也方便测试时替换。
 *
 * 为什么用 BCrypt 而不是 MD5/SHA？
 *   - MD5/SHA 是"快速哈希"，攻击者可以暴力/彩虹表秒破；
 *   - BCrypt 是"慢哈希"，内部带随机盐（salt）+ 可调计算强度，同样的密码每次加密结果都不同，
 *     破解成本高得多。Spring Security 官方推荐的密码加密器就是 BCryptPasswordEncoder。
 *
 * 随机盐：即使两个人密码相同，BCrypt 存出来的字符串也不一样（盐不同），
 * 所以"登录比对"不能用 ==/equals，要用 encoder.matches(明文, 密文)。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 默认强度 10（计算 2^10 轮），可传 4~31。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
