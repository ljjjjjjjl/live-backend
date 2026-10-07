package com.live.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置：自定义 RedisTemplate 的序列化方式。
 *
 * <p>Spring Boot 默认注入的 StringRedisTemplate 只存字符串；而我们希望：
 * <ul>
 *   <li>key 用 {@link StringRedisSerializer}（可读，避免默认 JDK 序列化出乱码/二进制）</li>
 *   <li>value 用 {@link GenericJackson2JsonRedisSerializer}（存 JSON，可存对象，Redis 里可直接看）</li>
 * </ul>
 *
 * <p>注意：value 存 JSON 时，字符串类会带上双引号/类型信息；若只存纯字符串（如 id/计数），
 * 推荐直接用 {@link StringRedisTemplate}（Spring Boot 自动配置）。这里为通用起见给个模板。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // key / hashKey 用字符串序列化（可读）
        StringRedisSerializer stringSer = new StringRedisSerializer();
        template.setKeySerializer(stringSer);
        template.setHashKeySerializer(stringSer);

        // value / hashValue 用 JSON 序列化（可存对象）
        GenericJackson2JsonRedisSerializer jsonSer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSer);
        template.setHashValueSerializer(jsonSer);

        template.afterPropertiesSet();
        return template;
    }
}
