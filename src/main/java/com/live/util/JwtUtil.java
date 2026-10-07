package com.live.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 工具类：负责「签发 Token」和「解析校验 Token」。
 *
 * Token 里只放不敏感的身份信息（userId / username），别放密码等敏感数据——
 * JWT 只是 Base64 编码，能看懂、只是改不了（有签名），但没加密。
 *
 * 方法：
 *   generateToken(userId, username)              签发 Token（首次登录：loginAt = 当前时间）
 *   generateToken(userId, username, loginAt)     签发 Token（滑动续期：沿用首次登录时间）
 *   parseToken(token)                            校验 + 解析，返回 userId；无效/过期返回 null
 *   getUsername(token)                           取 username
 *   getRemainingMillis(token)                    还剩多少毫秒过期（续期判断用）
 *   getLoginAt(token)                            取首次登录时间戳（绝对上限判断用）
 *
 * <p><b>关于滑动续期（重点）</b>：
 * JWT 的 {@code exp} 在签发那一刻就写死在 Token 里，服务端**无法修改**已签发的 Token
 * （改了签名就对不上），所以"用户活跃就延长有效期"的唯一实现方式是：
 * <b>临近过期时签发一个新 Token 下发给前端替换旧的</b>。
 *
 * <p>但这样会带来一个新问题——只要用户一直点，Token 就一直换，会话**永不结束**。
 * 所以 Token 里额外记了一个 {@code loginAt}（首次登录时间），续期时**原样带过去**，
 * 服务端据此算出"这个会话总共活了多久"，超过绝对上限就不再续期，强制重新登录。
 */
@Component
public class JwtUtil {

    /** claim 名：首次登录时间戳。续期时保持不变，用于计算会话绝对上限 */
    private static final String CLAIM_LOGIN_AT = "loginAt";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire}")
    private long expire;

    /** 根据配置的 secret 生成签名密钥（HS256 要求 ≥32 字节） */
    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Token（首次登录）：loginAt = 当前时间。
     *
     * <p>登录接口用这个重载；滑动续期用下面带 loginAt 的那个。
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, System.currentTimeMillis());
    }

    /**
     * 签发 Token（滑动续期）：loginAt 沿用**首次登录**的时间戳。
     *
     * <p>为什么不直接用当前时间当 loginAt？那样每次续期都会"重新开始计时"，
     * 会话就永远没有尽头了。沿用首次登录时间，服务端才能判断"这个会话活了多久"。
     *
     * @param loginAt 首次登录时间戳（毫秒）
     */
    public String generateToken(Long userId, String username, long loginAt) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expire);
        return Jwts.builder()
                .setSubject(String.valueOf(userId))   // sub = userId（主题）
                .claim("username", username)          // 自定义声明：username
                .claim(CLAIM_LOGIN_AT, loginAt)       // 首次登录时间（续期时不变）
                .setIssuedAt(now)                     // iat 签发时间（每次续期都会更新）
                .setExpiration(expireDate)            // exp 过期时间（每次续期都往后推）
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Claims 的私有统一入口。
     * 签名错误 / 已过期 / 格式非法 一律返回 null，调用方不用写 try-catch。
     */
    private Claims parseClaims(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            // 过期 / 签名不对 / 被篡改 统统当无效
            return null;
        }
    }

    /**
     * 校验并解析 Token，返回 userId。
     * 签名错误 / 过期 / 格式非法 都会返回 null（表示无效）。
     */
    public Long parseToken(String token) {
        Claims claims = parseClaims(token);
        return claims == null ? null : Long.valueOf(claims.getSubject());
    }

    /**
     * 从 Token 里取 username（可选，方便前端展示；不放敏感信息即可）。
     */
    public String getUsername(String token) {
        Claims claims = parseClaims(token);
        return claims == null ? null : claims.get("username", String.class);
    }

    /**
     * Token 还剩多少毫秒过期。
     *
     * <p>拦截器用它判断"是否该续期了"。无效或已过期返回 {@code -1}。
     *
     * @return 剩余毫秒数；无效/过期返回 -1
     */
    public long getRemainingMillis(String token) {
        Claims claims = parseClaims(token);
        if (claims == null || claims.getExpiration() == null) {
            return -1;
        }
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }

    /**
     * 取"首次登录时间"（毫秒）。续期时用来算会话总时长。
     *
     * <p>兼容性：本功能上线前签发的旧 Token 没有 {@code loginAt} 声明，
     * 这里回退用 {@code iat}（签发时间），不会报错。
     *
     * @return 首次登录时间戳；Token 无效返回 -1
     */
    public long getLoginAt(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return -1;
        }
        Object at = claims.get(CLAIM_LOGIN_AT);
        if (at instanceof Number) {
            return ((Number) at).longValue();
        }
        return claims.getIssuedAt() == null ? -1 : claims.getIssuedAt().getTime();
    }
}
