package com.live.config;

import com.live.websocket.DanmakuHandshakeInterceptor;
import com.live.websocket.DanmakuWebSocketHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置，负责两件事：
 *   1) @EnableWebSocket 启用 WebSocket 支持；
 *   2) 注册弹幕 Handler 到路径 /ws/danmaku/{roomId}，
 *      并挂上握手拦截器（解析 roomId + 校验 token 取 userId）和允许跨域。
 *
 * 注册路径用一个"占位符 {roomId}"，这样多个直播间共用同一个 Handler，
 * 区分直播间的依据是握手时提取到的 roomId（存在 WebSocketSession.attributes 里）。
 *
 * <h3>跨域放行范围改为可配置（本轮修复）</h3>
 * 原来写死 {@code setAllowedOriginPatterns("*")}：任意站点都能从浏览器发起 WS 连接，
 * 典型的 CSWSH（Cross-Site WebSocket Hijacking）面。虽然本项目的 Token 放在子协议/URL 里、
 * 不会随 Cookie 自动携带，风险比传统会话型 WebSocket 低，但"开发期放开、上线前忘了改"
 * 是最常见的生产事故之一。
 *
 * <p>现在改成配置项 {@code app.ws.allowed-origin-patterns}（默认 {@code *} 方便本地演示），
 * 并在**启动时**如果发现是 {@code *} 就打一条 WARN —— 让它在日志里显式可见，
 * 而不是潜伏在代码里等着上线。
 *
 * 注意：DanmakuWebSocketHandler 和 DanmakuHandshakeInterceptor 都是 @Component Bean
 * （后者依赖 JwtUtil），所以都 @Autowired 注入，不能自 new —— new 出来的实例没有注入依赖。
 */
@Slf4j
@Configuration
@EnableWebSocket
public class DanmakuWebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private DanmakuWebSocketHandler danmakuWebSocketHandler;

    @Autowired
    private DanmakuHandshakeInterceptor danmakuHandshakeInterceptor;

    /** 允许建立 WS 连接的来源；默认 * 只为本地演示，生产必须改成自己的前端域名 */
    @Value("${app.ws.allowed-origin-patterns:*}")
    private String[] allowedOriginPatterns;

    @PostConstruct
    public void warnIfWildcard() {
        for (String p : allowedOriginPatterns) {
            if ("*".equals(p.trim())) {
                log.warn("【WebSocket 跨域】app.ws.allowed-origin-patterns 当前为 *（允许任意站点连接）。"
                        + "本地演示没问题，上线前务必改成真实前端域名，例如 https://live.example.com");
                return;
            }
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(danmakuWebSocketHandler, "/ws/danmaku/{roomId}")
                .addInterceptors(danmakuHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
