package com.live.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.common.BusinessException;
import com.live.entity.Danmaku;
import com.live.service.DanmakuService;
import com.live.vo.DanmakuMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 弹幕 WebSocket 处理器。
 *
 * 核心职责：**按直播间管理在线会话**，收到的弹幕先落库，再广播给该直播间所有在线用户。
 *
 * 结构用一个 Map：roomId -> Set<WebSocketSession>，天然地"按直播间隔离"，
 * 弹幕只会在"同一个直播间"的会话之间转发，不会串房间。
 *
 * 线程安全点（面试常问）：
 *   - 用 ConcurrentHashMap 存 roomId -> 会话集合，允许多个连接同时 join 同一房间；
 *   - 每个房间的会话集合用 CopyOnWriteArraySet，广播遍历时不怕并发增删；
 *   - sendMessage 不是线程安全的，所以多线程广播到同一会话时要加锁。
 *
 * 为什么"发弹幕"同时要做落库 + 广播两件事？——这就是为什么这一层（而不是纯 Handler）是核心：
 *   落库 → 别人后进直播间能看到历史弹幕；
 *   广播 → 当前在线的人能实时看到新弹幕。
 *   两者要"要么都成功要么都失败"才一致，所以落库方法上加了 @Transactional（见 DanmakuService）。
 */
@Slf4j
@Component
public class DanmakuWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DanmakuService danmakuService;

    /** roomId -> 该直播间当前所有在线会话集合 */
    private final Map<Long, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    /**
     * 连接建立：把会话加进对应直播间。
     * 从握手时放入的 attributes 里读出 roomId。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long roomId = roomIdOf(session);
        if (roomId == null) {
            session.close(); // 没有 roomId，拒绝连接
            return;
        }
        roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>())
                .add(session);

        sendSystem(session, "已进入直播间");
    }

    /**
     * 收到一条文本消息：前端发来 JSON -> 解析 -> 落库 -> 广播。
     * 前端消息格式：{"userId":1,"nickname":"小明","content":"主播好","color":"#ff0000"}
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long roomId = roomIdOf(session);
        if (roomId == null) return;

        String payload = message.getPayload();

        // 解析成通用 Map，避免为弹幕单独造请求体类（也可以建个 DTO，但这里省事）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(payload, Map.class);

        String content = data.get("content") == null ? "" : String.valueOf(data.get("content"));
        if (content.trim().isEmpty()) {
            sendSystem(session, "弹幕内容不能为空");
            return;
        }

        // 组装实体（roomId 和 userId 都从连接身份里拿，不给前端乱传的机会）
        Danmaku danmaku = new Danmaku();
        danmaku.setRoomId(roomId);
        danmaku.setUserId(userIdOf(session));            // 身份来自握手时校验的 Token，不是消息体
        danmaku.setNickname(String.valueOf(data.getOrDefault("nickname", "游客")));
        danmaku.setContent(content);
        danmaku.setColor(String.valueOf(data.getOrDefault("color", "#ffffff")));
        danmaku.setCreateTime(LocalDateTime.now());

        // ① 落库（事务，见 DanmakuService.save）—— 保证后进直播间的人能看到历史
        //    ⚠️ 必须捕获异常：save 命中敏感词会抛 BusinessException。
        //    REST 通道有 GlobalExceptionHandler 兜底，但 WebSocket 通道【没有】——
        //    异常会冒泡到 Spring 的 WS 层，通常表现为连接被关闭，而用户什么提示都看不到。
        //    所以这里要主动把失败原因回给发送者，并且【不广播】。
        try {
            danmaku = danmakuService.save(danmaku);
        } catch (BusinessException e) {
            sendSystem(session, "发送失败：" + e.getMessage());
            return;
        } catch (Exception e) {
            log.error("弹幕落库失败 roomId={} userId={}", roomId, userIdOf(session), e);
            sendSystem(session, "发送失败，请稍后再试");
            return;
        }

        // ② 广播给当前直播间所有人 —— 保证在线的人实时能看到
        broadcastToRoom(roomId, danmaku);
    }

    /**
     * 连接关闭：把会话从直播间移除。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long roomId = roomIdOf(session);
        if (roomId == null) return;
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    /**
     * 广播一条弹幕给某个直播间内的所有在线会话。
     */
    private void broadcastToRoom(Long roomId, Danmaku danmaku) {
        DanmakuMessage msg = new DanmakuMessage();
        msg.setType("CHAT");
        msg.setData(danmaku);

        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(msg);
            // 同一 WebSocket 上并发 send 会抛异常，逐个会话加锁发送
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                }
            }
        } catch (Exception e) {
            // 网络异常/会话已断，忽略，下次连接清理时自动移除
        }
    }

    /**
     * 给单个连接发一条系统提示（进房间、错误提示等）。
     */
    private void sendSystem(WebSocketSession session, String text) throws Exception {
        if (!session.isOpen()) return;
        DanmakuMessage msg = new DanmakuMessage();
        msg.setType("SYSTEM");
        msg.setData(text);
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        }
    }

    /** 从会话里取 roomId（由握手拦截器放入 attributes） */
    private Long roomIdOf(WebSocketSession session) {
        Object v = session.getAttributes().get("roomId");
        if (v == null) return null;
        return toLong(v);
    }

    /** 从会话里取 userId（由握手拦截器从 Token 解析后放入 attributes） */
    private Long userIdOf(WebSocketSession session) {
        Object v = session.getAttributes().get("userId");
        if (v == null) return null;
        return toLong(v);
    }

    /** 把 Object 安全转 Long */
    private Long toLong(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
