package com.live.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
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
 *
 * <h2>⚠️ 性能：解析器和密钥必须复用，不能每个请求 new（压测定位出来的真瓶颈）</h2>
 * <p>改造前每次解析都写成了一条链：
 * <pre>
 *   Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token)
 * </pre>
 * 这条链每次调用都会<b>新建一个 parser</b>，而 {@code build()} 内部要初始化 JSON 序列化器
 * （Jackson ObjectMapper）。ObjectMapper 的构造是毫秒级的 —— 它要注册模块、构建
 * SerializerProvider/DeserializerCache 等一整套东西。于是「验一个 200 字节的 HMAC」
 * 这个本该几十微秒的操作，实际要花 <b>约 4ms</b>。
 *
 * <p>实测依据（单机压测，逐层对照摘出来的）：
 * <table border="1">
 *   <tr><th>请求</th><th>是否验签</th><th>P50</th></tr>
 *   <tr><td>GET /api/live/{room}/online（游客放行，只读一次 Redis）</td>
 *       <td>否</td><td>1.56 ms</td></tr>
 *   <tr><td>GET /api/live/__probe__（要登录，验签通过后 404，无任何业务逻辑）</td>
 *       <td>是</td><td>5.08 ms</td></tr>
 *   <tr><td>GET /api/live/__probe__ 不带 Token（拦截器立刻打回）</td>
 *       <td>否</td><td>0.75 ms</td></tr>
 * </table>
 *
 * <p>「验签通过但什么业务都不做」比「游客放行且要读 Redis」还慢 3.5ms ——
 * 唯一的差别就是验签，所以这 3.5ms 只能是解析器构造的开销。
 * 它同时也是整个接口的吞吐瓶颈：并发 50 / 200 / 600 三档压下来吞吐都在 1250~1360，
 * 完全不随并发变化，说明瓶颈是「串行/CPU 密集的单次开销」，而不是排队。
 *
 * <p>修法就是把这两样东西各建一次然后复用：
 * <ul>
 *   <li>{@link Key}：由 secret 派生，内容永远不变；</li>
 *   <li>{@link JwtParser}：jjwt 的 Parser 构造后是<b>不可变</b>的，官方说明可多线程共享。</li>
 * </ul>
 * 两者都用「懒初始化 + 双重检查」：因为它们依赖 {@code @Value} 注入的字段，
 * 注入发生在构造之后，所以不能在构造函数里初始化。
 */
@Component
public class JwtUtil {

    /** claim 名：首次登录时间戳。续期时保持不变，用于计算会话绝对上限 */
    private static final String CLAIM_LOGIN_AT = "loginAt";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire}")
    private long expire;

    /**
     * 缓存的签名密钥。
     * 加 volatile + 双重检查：多线程同时第一次访问时只会真的创建一次，
     * 之后的读写不再加锁（本方法在每秒上千次的热路径上，不能每次都进 synchronized）。
     */
    private volatile Key cachedKey;

    /**
     * 缓存的解析器。
     * 这是本类最重要的一个字段 —— 它的构造占了整个接口一半以上的耗时（见类注释）。
     */
    private volatile JwtParser cachedParser;

    /** 根据配置的 secret 生成签名密钥（HS256 要求 ≥32 字节）。<b>只创建一次</b>，之后复用 */
    private Key getKey() {
        Key k = cachedKey;
        if (k == null) {
            synchronized (this) {
                k = cachedKey;
                if (k == null) {
                    k = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                    cachedKey = k;
                }
            }
        }
        return k;
    }

    /**
     * 取共享的解析器。<b>只创建一次</b>，之后复用。
     *
     * <p>jjwt 的 {@link JwtParser} 构造完成后是不可变对象，可以安全地被多个线程同时调用，
     * 所以这里用单例即可，不需要 ThreadLocal。
     */
    private JwtParser getParser() {
        JwtParser p = cachedParser;
        if (p == null) {
            synchronized (this) {
                p = cachedParser;
                if (p == null) {
                    p = Jwts.parserBuilder()
                            .setSigningKey(getKey())
                            .build();
                    cachedParser = p;
                }
            }
        }
        return p;
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
     * <p>注意：签发这条路仍然每次 {@code new} 一个 builder（jjwt 的 builder 是有状态的，
     * 不能跨请求复用），但签发只在登录和"临近过期"时发生，不在每个请求的热路径上；
     * 热路径上是 {@link #parseClaims}，那条路已经复用了解析器。
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
            return getParser().parseClaimsJws(token).getBody();
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
     * Token 解析结果（一次解析拿到全部需要的字段）。
     *
     * @param userId          用户ID（Token 的 sub）
     * @param username        用户名
     * @param remainingMillis 还剩多少毫秒过期（可能为负，表示已过期）
     * @param loginAt         首次登录时间戳（毫秒），用于算会话绝对上限
     */
    public record TokenInfo(Long userId, String username, long remainingMillis, long loginAt) {
    }

    /**
     * <b>一次</b>解析出拦截器需要的全部信息。
     *
     * <p>为什么要加这个方法：JWT 的解析＝"Base64 解码 + HMAC 验签"，是纯 CPU 活。
     * 原来 {@code JwtInterceptor} 在每个需要登录的请求里分别调了
     * {@code parseToken / getUsername / getRemainingMillis / getLoginAt}，
     * 而它们内部各自 {@code parseClaims} 一遍 —— 等于<b>每个请求验签 4 次</b>。
     * 高 QPS 下这就是白烧的 CPU（也是压测时容易被忽略的一个损耗点）。
     *
     * @return 无效 Token 返回 null
     */
    public TokenInfo parseTokenInfo(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;   // sub 不是数字 = 不是我们签发的 Token
        }
        long remaining = claims.getExpiration() == null
                ? -1
                : claims.getExpiration().getTime() - System.currentTimeMillis();
        return new TokenInfo(userId, claims.get("username", String.class), remaining, loginAtOf(claims));
    }

    /** 从已解析的 Claims 里取"首次登录时间"（兼容没有 loginAt 声明的旧 Token） */
    private long loginAtOf(Claims claims) {
        Object at = claims.get(CLAIM_LOGIN_AT);
        if (at instanceof Number) {
            return ((Number) at).longValue();
        }
        return claims.getIssuedAt() == null ? -1 : claims.getIssuedAt().getTime();
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
        return loginAtOf(claims);
    }
}
