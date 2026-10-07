package com.live.config;

import com.live.websocket.DanmakuHandshakeInterceptor;
import com.live.websocket.DanmakuWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
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
 * ⚠️ 第三方工具测试时需要注意跨域；这里 setAllowedOriginPatterns("*") 是开发期放开，
 *   上线前要改成真实域名或收窄到自己的前端地址。
 *
 * 注意：DanmakuWebSocketHandler 和 DanmakuHandshakeInterceptor 都是 @Component Bean
 * （后者依赖 JwtUtil），所以都 @Autowired 注入，不能自 new —— new 出来的实例没有注入依赖。
 */
@Configuration
@EnableWebSocket
public class DanmakuWebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private DanmakuWebSocketHandler danmakuWebSocketHandler;

    @Autowired
    private DanmakuHandshakeInterceptor danmakuHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(danmakuWebSocketHandler, "/ws/danmaku/{roomId}")
                .addInterceptors(danmakuHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
