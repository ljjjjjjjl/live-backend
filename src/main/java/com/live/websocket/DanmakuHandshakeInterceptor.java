package com.live.websocket;

import com.live.service.UserService;
import com.live.service.WsTicketService;
import com.live.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 弹幕握手拦截器：连接建立时把「是谁」和「在哪个房间」钉在 session 上。
 *
 * <ol>
 *   <li>从路径 {@code /ws/danmaku/{roomId}} 取出 roomId —— 必须是<b>正整数</b>，
 *       否则直接拒绝握手（原来不做校验，roomId 传个 "abc" 会在每条消息里抛
 *       NumberFormatException）；</li>
 *   <li>认身份（三种入口，按优先级）：
 *     <ol>
 *       <li><b>{@code ?ticket=}（前端在用）</b> —— 页面先带 Authorization 头调
 *           {@code POST /api/danmaku/ws-ticket} 换一张 60 秒、一次性的票据，
 *           再拿它来握手。这样 JWT 永远不会出现在 URL / 访问日志里；</li>
 *       <li>{@code ?token=<JWT>} —— 兼容既有脚本（如 {@code ws-test.js}）；</li>
 *       <li>{@code Sec-WebSocket-Protocol: dsh.token.<JWT>} —— 见下面常量上的说明，
 *           当前 Spring 不会回选未注册的子协议，所以浏览器侧握手会失败，仅保留解析逻辑。</li>
 *     </ol>
 *   </li>
 *   <li>顺手把<b>服务端查出来的昵称</b>和<b>凭证过期时刻</b>放进 attributes，
 *       后面发消息就不用再查库、也能判断"这条连接是不是早就该失效了"。</li>
 * </ol>
 *
 * <h3>为什么不再把 JWT 直接拼在 URL 上</h3>
 * URL 会进 Tomcat access log、代理日志、以及 Referer，等于把"有效期 2 小时的登录凭证"
 * 撒到一堆日志里。改成一次性票据后，即使日志泄漏，泄漏的也只是一张 60 秒内、只能用一次的废纸。
 *
 * <p>注意：{@code @Component} 让它成为 Bean，从而能注入 JwtUtil（依赖注入）。
 */
@Slf4j
@Component
public class DanmakuHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 子协议前缀：{@code dsh.token.<JWT>}
     *
     * <p>⚠️ 这条路径在当前 Spring/Tomcat 组合下<b>握手会失败</b>：客户端声明了子协议，
     * 服务端必须回选一个同名子协议，否则浏览器会主动断开。而 Spring 的
     * {@code AbstractHandshakeHandler#selectProtocol} 只会回选"已注册支持的协议"
     * （{@code setSupportedProtocols}），而我们的 Token 是每用户不同的、无法预先注册。
     * 所以这里保留解析逻辑（方便日后接入真实的子协议方案），但<b>前端不再使用它</b>，
     * 真正在用的是下面的一次性票据（{@link WsTicketService}）。
     */
    private static final String TOKEN_PROTOCOL_PREFIX = "dsh.token.";

    /** attributes 里的键名（Handler 与拦截器共用） */
    public static final String ATTR_ROOM_ID = "roomId";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_NICKNAME = "nickname";
    public static final String ATTR_EXPIRE_AT = "tokenExpireAt";

    /** 票据登录时，这条连接的"凭证有效期"按 12 小时算（和 jwt.max-lifetime 的会话上限一致） */
    private static final long TICKET_SESSION_MILLIS = 12L * 60 * 60 * 1000;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserService userService;

    @Autowired
    private WsTicketService wsTicketService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // ① 路径形如 /ws/danmaku/101，取最后一段并校验是正整数
        Long roomId = parseRoomId(request.getURI().getPath());
        if (roomId == null || roomId <= 0) {
            log.warn("弹幕握手被拒：直播间ID非法 path={}", request.getURI().getPath());
            return false;
        }

        // ② 认身份：**一次性票据优先**（前端用的就是这条），其次才兼容 JWT 直传
        String rawQuery = request.getURI().getRawQuery();
        Long userId = null;
        long expireAt = 0L;

        Long ticketUserId = wsTicketService.consume(getQueryParam(rawQuery, "ticket"));
        if (ticketUserId != null) {
            userId = ticketUserId;
            expireAt = System.currentTimeMillis() + TICKET_SESSION_MILLIS;
            log.debug("弹幕握手用一次性票据通过 roomId={} userId={}", roomId, userId);
        } else {
            // 兼容路径：JWT 放子协议 / 放 query（?token=，ws-test.js 等脚本在用）
            String subProtocolToken = tokenFromSubProtocol(request);
            String token = subProtocolToken != null ? subProtocolToken : getQueryParam(rawQuery, "token");
            JwtUtil.TokenInfo info = jwtUtil.parseTokenInfo(token);
            if (info != null && info.userId() != null) {
                userId = info.userId();
                expireAt = System.currentTimeMillis() + Math.max(0, info.remainingMillis());
            }
        }

        if (userId == null) {
            // 票据无效/过期/已用过，或 Token 无效 → 拒绝握手（连接建立不起来）
            return false;
        }

        // ③ 把房间、身份、昵称、凭证过期时刻钉在 session 上
        attributes.put(ATTR_ROOM_ID, roomId);
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_NICKNAME, userService.displayName(userId));
        attributes.put(ATTR_EXPIRE_AT, expireAt);
        return true; // 允许握手
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需处理
    }

    /** 从路径最后一段解析 roomId；非数字返回 null（不抛异常） */
    private Long parseRoomId(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length == 0) {
            return null;
        }
        String last = parts[parts.length - 1];
        try {
            return Long.valueOf(last);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 {@code Sec-WebSocket-Protocol} 里取 Token（形如 {@code dsh.token.xxx}）。
     *
     * <p>客户端可能声明多个子协议，按逗号分隔逐个找前缀匹配的那个。
     */
    private String tokenFromSubProtocol(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null) {
            return null;
        }
        for (String header : protocols) {
            for (String p : header.split(",")) {
                String v = p.trim();
                if (v.startsWith(TOKEN_PROTOCOL_PREFIX) && v.length() > TOKEN_PROTOCOL_PREFIX.length()) {
                    return v.substring(TOKEN_PROTOCOL_PREFIX.length());
                }
            }
        }
        return null;
    }

    /**
     * 从原始 query 串里取某个参数（并做<b>URL 解码</b> —— 原来漏了解码，
     * 一旦前端对 Token 做了 percent-encoding，签名校验就会失败）。
     */
    private String getQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
