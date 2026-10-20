package com.live.config;

import com.live.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.regex.Pattern;

/**
 * JWT 登录拦截器：统一校验请求头里的 Token，并负责**滑动续期**。
 *
 * 作用：
 *   ① 从请求头 Authorization: Bearer &lt;token&gt; 里取出 Token；
 *   ② 用 JwtUtil 校验（签名 + 过期），无效就返回 401 并拦截；
 *   ③ 有效就把 userId / username 放到 request 里，供 Controller 用 @RequestAttribute 取；
 *   ④ <b>滑动续期</b>：若 Token 临近过期且未超会话上限 → 签发新 Token 放进响应头
 *      {@value #REFRESH_HEADER}，前端收到就替换本地的，从而实现"活跃期间不过期"。
 *
 * 游客放行：只读展示类接口允许游客访问（不校验 token），名单见 {@link #GUEST_READABLE_PATHS}
 *   和 {@link #GUEST_READABLE_PATTERN} —— 例如 GET /api/live/rooms（列表）、GET /api/danmaku（历史弹幕）。
 *   注意：这里按【方法 + 路径】精确放行，POST /api/live/rooms(创建)、/start、/stop 仍需登录。
 *
 * 哪些接口要登录，在 WebConfig 里配置（白名单：登录/注册不拦）。
 */
@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /**
     * 响应头名：服务端下发的新 Token。
     * 前端（app.js）发现这个头就用它覆盖本地 localStorage 里的旧 Token。
     */
    public static final String REFRESH_HEADER = "X-Refresh-Token";

    @Autowired
    private JwtUtil jwtUtil;

    /** 单次 Token 有效期（毫秒），仅用于日志展示和上限计算 */
    @Value("${jwt.expire}")
    private long expire;

    /**
     * 滑动续期阈值（毫秒）：Token 剩余有效期 ≤ 该值时自动签发新 Token。
     *
     * <p>不能"每个请求都续期"——那样会疯狂签发 Token，白白浪费 CPU 和签名开销。
     * 所以设一个阈值：用到快过期了才续。<b>0 表示关闭滑动续期</b>（退回固定过期）。
     */
    @Value("${jwt.refresh-threshold:0}")
    private long refreshThreshold;

    /**
     * 会话绝对上限（毫秒）：从**首次登录**算起，续期后的 Token 若会超出该上限就不再续期，
     * 强制用户重新登录。
     *
     * <p>为什么必须有：否则用户挂着页面不断发请求，会话就**永远不会结束**，安全性太差。
     * <b>0 表示不限制</b>。
     */
    @Value("${jwt.max-lifetime:0}")
    private long maxLifetime;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 对 OPTIONS 预检请求放行（浏览器跨域会先发 OPTIONS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 游客可读接口放行：看直播本身就是"无需登录"的浏览行为
        if (isGuestReadable(request.getMethod(), request.getRequestURI())) {
            return true;
        }

        // ① 取请求头 Authorization: Bearer xxx
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(response, "未登录");
        }
        // ② 校验 Token —— **只解析一次**（原来为了拿 userId/username/剩余时间/loginAt
        //    分别调了 4 个方法，每个内部都 parseClaims 一遍，等于每请求验签 4 次）
        String token = authHeader.substring("Bearer ".length());
        JwtUtil.TokenInfo info = jwtUtil.parseTokenInfo(token);
        if (info == null) {
            return reject(response, "Token无效或已过期");
        }

        // ③ 把身份放进 request，Controller 用 @RequestAttribute("userId") 取
        request.setAttribute("userId", info.userId());
        request.setAttribute("username", info.username());

        // ④ 滑动续期：临近过期就换发新 Token（通过响应头下发）
        refreshIfNeeded(response, info);

        return true; // 放行
    }

    /**
     * 滑动续期核心逻辑。
     *
     * <p>为什么是"换发新 Token"而不是"延长旧 Token"？因为 JWT 的 exp 写在 Token 内部并且被签名保护，
     * 服务端改了 exp 签名就对不上，前端那边的旧 Token 也依然是旧 exp。
     * <b>无状态 Token 的续期 = 重新签一个。</b>
     *
     * <p>触发条件（两个都满足才续）：
     * <ol>
     *   <li>剩余有效期 ≤ {@code refresh-threshold}（快过期了）；</li>
     *   <li>续期后的过期时间仍在会话绝对上限内（{@code loginAt + max-lifetime} 之内）。</li>
     * </ol>
     */
    private void refreshIfNeeded(HttpServletResponse response, JwtUtil.TokenInfo info) {
        // 阈值为 0 → 关闭滑动续期
        if (refreshThreshold <= 0) {
            return;
        }

        long remaining = info.remainingMillis();
        // -1 = 无效/已过期（前面已拦掉）；剩余还多 → 不用续
        if (remaining < 0 || remaining > refreshThreshold) {
            return;
        }

        Long userId = info.userId();
        String username = info.username();
        long loginAt = info.loginAt();
        long now = System.currentTimeMillis();

        // 会话绝对上限：续期后新 Token 的过期时刻 = now + expire
        // 若它超出 loginAt + maxLifetime，就不再续 —— 保证会话硬性截止在首次登录 + maxLifetime
        if (maxLifetime > 0 && loginAt > 0 && now + expire > loginAt + maxLifetime) {
            log.info("用户 {} 的会话已达绝对上限（{} 分钟），不再续期，需重新登录",
                    userId, maxLifetime / 60000);
            return;
        }

        String fresh = jwtUtil.generateToken(userId, username,
                loginAt > 0 ? loginAt : now);   // loginAt 原样带过去，会话计时不重置

        response.setHeader(REFRESH_HEADER, fresh);
        // 跨域场景下浏览器默认不暴露自定义响应头，显式声明（同源请求下无害）
        response.setHeader("Access-Control-Expose-Headers", REFRESH_HEADER);

        log.info("用户 {} 的 Token 已滑动续期：原剩余 {} 秒，已换发新 Token（有效期 {} 分钟）",
                userId, remaining / 1000, expire / 60000);
    }

    /**
     * 游客可读的<b>只读展示类</b>接口名单（仅 GET 放行）。
     *
     * <p><b>为什么需要这个名单？</b>
     * WebConfig 是按前缀拦截的：{@code /api/live/**}、{@code /api/danmaku/**} 一律要登录。
     * 但"看直播间列表 / 看在线人数 / 看礼物榜 / 拉历史弹幕"这些<b>浏览行为本身不需要身份</b>，
     * 一刀切会逼着游客先登录才能看，体验和真实直播站不符。
     *
     * <p>所以这里按【方法 + 精确路径】做白名单，只放行只读接口；
     * <b>写操作</b>（创建/开播/关播/送礼/进房/发弹幕）依旧必须带 Token —— 那些要确定"是谁"。
     *
     * <p>⚠️ 注意：这里只判断"能不能不登录访问"，不改变 Controller 里 userId 的来源。
     * 需要身份的接口仍然从 Token 取 userId，绝不接受前端传参。
     */
    private static final List<String> GUEST_READABLE_PATHS = List.of(
            "/api/live/rooms",       // 直播间列表（分页 + 分区）
            "/api/live/recommend",   // 推荐直播间
            "/api/danmaku"           // 历史弹幕（分页拉取）
    );

    /** 带路径参数的游客只读接口：/api/live/{roomId}/online、/api/live/{roomId}/like、/api/live/{roomId}/gift/rank */
    private static final Pattern GUEST_READABLE_PATTERN =
            Pattern.compile("^/api/live/[^/]+/(online|like|gift/rank)$");

    /**
     * 判断是否属于「游客可读」接口。
     *
     * <p>只放行 GET —— 用 POST 打同样的路径（例如 POST /api/live/1/enter）仍然要登录。
     */
    private boolean isGuestReadable(String method, String uri) {
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return GUEST_READABLE_PATHS.contains(uri) || GUEST_READABLE_PATTERN.matcher(uri).matches();
    }

    /** 拒绝并返回 401 + 统一 JSON */
    private boolean reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 复用统一返回结构 {code, msg, data}
        response.getWriter().write("{\"code\":1,\"msg\":\"" + msg + "\",\"data\":null}");
        return false;
    }
}
