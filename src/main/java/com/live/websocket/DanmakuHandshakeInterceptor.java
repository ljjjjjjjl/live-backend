package com.live.websocket;

import com.live.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 弹幕握手拦截器：
 *   1) 连接路径 /ws/danmaku/{roomId} → 从路径里取出 roomId 存进 attributes；
 *   2) 从 URL query 里的 token 校验登录 → 解析出 userId 存进 attributes（身份来自 Token）。
 *
 * 为什么身份放在握手时解析、而不是在 Handler 里信前端消息体？
 *   WebSocket 连接建立时就能确定"这条连接是谁"，把它钉在 session 上，
 *   之后发弹幕从连接身份取 userId，前端伪造不了。这和你 HTTP 端 JWT 拦截器是同一套思想。
 *
 * 注意：@Component 让它成为 Bean，从而能注入 JwtUtil（依赖注入）。
 */
@Component
public class DanmakuHandshakeInterceptor implements HandshakeInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // ① 路径形如 /ws/danmaku/101，取最后一段作为 roomId
        String path = request.getURI().getPath();
        String[] parts = path.split("/");
        if (parts.length > 0) {
            attributes.put("roomId", parts[parts.length - 1]);
        }

        // ② 从链接的 query 参数里取 token（WebSocket 浏览器不便带 Authorization 头）
        String token = getQueryParam(request.getURI(), "token");
        Long userId = jwtUtil.parseToken(token);
        if (userId == null) {
            // 未登录/Token 无效 → 拒绝握手（连接建立不起来）
            return false;
        }
        attributes.put("userId", userId);
        return true; // 允许握手
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需处理
    }

    /** 从 URL 里取某个 query 参数的值（如 ws://.../ws/danmaku/1?token=xxx → 取 token=xxx） */
    private String getQueryParam(URI uri, String key) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return kv[1];
            }
        }
        return null;
    }
}
