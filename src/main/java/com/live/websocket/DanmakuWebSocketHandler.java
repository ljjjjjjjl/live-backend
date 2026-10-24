package com.live.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.common.BusinessException;
import com.live.service.DanmakuService;
import com.live.vo.DanmakuMessage;
import com.live.vo.DanmakuPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹幕 WebSocket 处理器。
 *
 * 核心职责：**按直播间管理在线会话**，收到的弹幕先落库，再广播给该直播间所有在线用户。
 *
 * 结构用两层 Map：roomId -> (sessionId -> WebSocketSession)，天然地"按直播间隔离"，
 * 弹幕只会在"同一个直播间"的会话之间转发，不会串房间。
 *
 * 线程安全点（面试常问）：
 *   - 外层 ConcurrentHashMap 存 roomId -> 会话容器，允许多个连接同时 join 同一房间；
 *   - 内层也用 ConcurrentHashMap，以 sessionId 为 key：put / remove 都是 O(1) 且**没有写放大**
 *     （早期用 CopyOnWriteArraySet，每次增删都要复制整个数组，房间人多、进出频繁时开销明显）；
 *   - 建连 / 断连都走 {@code compute(...)}，把"建容器 + 增删元素 + 空了删 key"放进同一个临界区，
 *     避免两条路径交错时把会话挂到一个已被删除的容器上（僵尸会话，收不到任何广播）；
 *   - sendMessage 不是线程安全的，所以多线程广播到同一会话时要按 session 加锁。
 *
 * <h2>弹幕不落库了（本轮改造）</h2>
 * 原来这里收到一条弹幕后要「先 INSERT 落库、再广播」，落库是为了让后进直播间的人
 * 能看到历史弹幕。现在这个能力<b>整体移除</b>（见 {@code docs/plan/danmaku-no-persist.md}）：
 * 处理链变成「校验 → 构造载荷 → 广播」，<b>全程不碰数据库</b>。
 *
 * <p>随之消失的还有：历史弹幕接口、服务端代发接口、{@code DanmakuMapper}、
 * {@code entity.Danmaku}。保留下来的是 DFA 敏感词校验（见 {@link DanmakuService}）——
 * 它原来藏在落库方法里，如果跟着一起删，审核这道闸门就静默没了。
 *
 * <p>⚠️ 副作用（已知并接受）：刷新页面后弹幕面板是空的，没有历史可补。
 * 弹幕在这个项目里的定位是"瞬时聊天流"，不是可回溯的留言。
 */
@Slf4j
@Component
public class DanmakuWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DanmakuService danmakuService;

    /**
     * roomId -> (sessionId -> 该直播间当前在线的会话)。
     *
     * <p>内层为什么用 sessionId 而不是 session 对象本身：WebSocketSession 的 hashCode/equals
     * 在不同实现里并不总是稳定（有的按对象地址算），用稳定的 String id 做 key 语义更清楚，
     * 也让"按 id 移除"在广播顺手清理死连接时变得直接。
     */
    private final Map<Long, Map<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    /** 弹幕内容长度上限：与 REST 侧 {@code Danmaku.content} 的 {@code @Size(max=200)} 保持一致 */
    private static final int MAX_CONTENT_LENGTH = 200;

    /** 颜色格式：#rgb / #rrggbb（只允许这两种，别的都当默认色，避免把任意字符串写进库和 style） */
    private static final java.util.regex.Pattern COLOR_PATTERN =
            java.util.regex.Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    /** 默认弹幕颜色 */
    private static final String DEFAULT_COLOR = "#ffffff";

    /** 单条连接每秒最多发多少条弹幕（限速，不是去重） */
    private static final int MAX_MESSAGES_PER_SECOND = 5;

    /**
     * 每条连接的发送窗口：sessionId -> [窗口起点毫秒, 本窗口已发条数]。
     *
     * <p>为什么必须做这个限制：WebSocket 通道不像 HTTP 那样天然被"请求-响应"节奏约束住，
     * 一条连接可以在一毫秒内推几千条消息，每条都会走一次数据库 INSERT + 一次全房间广播。
     * 没有限速时，<b>一个人就能把整个房间的弹幕和数据库写满</b>（也能把广播放大成 N 倍）。
     *
     * <p>用固定窗口而不是漏桶：实现简单、语义够用（弹幕是"人类打字"级别的频率，
     * 每秒 5 条已经远超正常手速，只有脚本会撞上限）。
     */
    private final Map<String, long[]> sendWindows = new ConcurrentHashMap<>();

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

        // 用 compute 而不是 computeIfAbsent：把"建容器 / 取容器"和"put"放进同一个临界区。
        // computeIfAbsent 的 put 发生在锁外，可能和断连侧的"清空即删 key"交错，
        // 结果会话被放进一个已经从 roomSessions 里删掉的容器 —— 该用户从此收不到广播。
        roomSessions.compute(roomId, (k, sessions) -> {
            if (sessions == null) {
                sessions = new ConcurrentHashMap<>();
            }
            sessions.put(session.getId(), session);
            return sessions;
        });

        sendSystem(session, "已进入直播间");
    }

    /**
     * 收到一条文本消息：前端发来 JSON -> 解析 -> 落库 -> 广播。
     * 前端消息格式：{"userId":1,"nickname":"小明","content":"主播好","color":"#ff0000"}
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long roomId = roomIdOf(session);
        Long userId = userIdOf(session);
        if (roomId == null || userId == null) return;

        // ① WebSocket 是长连接，握手只校验一次 Token。
        //    不补这一步的话，一个 Token 过期几小时之后这条连接依然能无限发弹幕
        //    （HTTP 侧每个请求都会校验，WS 侧没有"每个请求"这个概念）。
        if (isTokenExpired(session)) {
            sendSystem(session, "登录已过期，请刷新页面重新登录");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // ② 限速：防刷屏，也防"一条连接把数据库写满 + 广播放大"
        if (!allowSend(session)) {
            sendSystem(session, "发得太快了，歇一下再发～");
            return;
        }

        // ③ 解析消息体（非法 JSON 只回提示，不抛异常把连接搞断）
        Map<String, Object> data;
        try {
            //noinspection unchecked
            data = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            sendSystem(session, "消息格式错误");
            return;
        }

        String content = data.get("content") == null ? "" : String.valueOf(data.get("content")).trim();
        if (content.isEmpty()) {
            sendSystem(session, "弹幕内容不能为空");
            return;
        }

        // ④ 长度校验：不拦的话，一条 10 万字的弹幕会被序列化一次、再写给房间里的每一个人
        //    （广播放大），前端渲染也会卡住。所以即便不再受数据库 varchar(255) 的约束，
        //    这个上限仍然必须保留 —— 它防的是"广播体积 + 渲染开销"，不是"入库失败"。
        if (content.length() > MAX_CONTENT_LENGTH) {
            sendSystem(session, "弹幕内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
            return;
        }

        // ⑤ 校验 + 组装载荷：
        //    roomId / userId 来自连接身份；
        //    nickname **来自握手时服务端查出来的值**（原来直接取消息体里的 nickname，
        //    等于任何人都能把昵称写成"主播"或别人的名字）。
        //    ⚠️ 必须捕获异常：命中敏感词会抛 BusinessException。
        //    REST 通道有 GlobalExceptionHandler 兜底，但 WebSocket 通道【没有】——
        //    异常会冒泡到 Spring 的 WS 层，通常表现为连接被关闭，而用户什么提示都看不到。
        //    所以这里要主动把失败原因回给发送者，并且【不广播】。
        DanmakuPayload payload;
        try {
            payload = danmakuService.checkAndBuild(
                    roomId, userId, nicknameOf(session), content, normalizeColor(data.get("color")));
        } catch (BusinessException e) {
            sendSystem(session, "发送失败：" + e.getMessage());
            return;
        } catch (Exception e) {
            log.error("弹幕校验/组装失败 roomId={} userId={}", roomId, userIdOf(session), e);
            sendSystem(session, "发送失败，请稍后再试");
            return;
        }

        // ⑥ 广播给当前直播间所有人 —— 不落库，所以这是这条弹幕唯一的去向
        broadcastToRoom(roomId, payload);
    }

    /**
     * 连接关闭：把会话从直播间移除。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(roomIdOf(session), session);
    }

    /**
     * 传输层异常（网络断开、发送失败等）。
     *
     * <p>Spring 的默认实现是把异常直接抛出去，会话的清理只能等 afterConnectionClosed。
     * 这里接管掉：先清理再关闭，保证异常路径下也不会在 roomSessions 里留下"僵尸会话"
     * （僵尸会话的 isOpen() 可能仍为 true，会一直参与广播并反复抛异常）。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        Long roomId = roomIdOf(session);
        log.warn("弹幕连接传输异常 sessionId={} roomId={}", session.getId(), roomId, exception);
        removeSession(roomId, session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 把会话从所属直播间移除：连接关闭、传输异常、广播时发现死连接，都走这里。
     *
     * <p>整个"移除元素 + 空了删外层 key"在 compute 的临界区内完成，是原子的；
     * 同时它是**幂等**的——会话已经不在时什么都不做，所以重复调用安全。
     *
     * @param roomId  直播间 ID（调用方已经从 session attributes 里取好）
     * @param session 要移除的会话
     */
    private void removeSession(Long roomId, WebSocketSession session) {
        if (roomId == null || session == null) return;
        String sessionId = session.getId();

        // 限速窗口是"每条连接一份"的，连接没了必须一起清掉，否则这个 Map 只增不减（内存泄漏）
        sendWindows.remove(sessionId);

        roomSessions.compute(roomId, (k, sessions) -> {
            if (sessions == null) {
                return null;              // 房间容器已经不存在（没人了/已被清理），无需新建
            }
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;   // 返回 null = 原子删除外层 key
        });
    }

    /**
     * 广播一条弹幕给某个直播间内的所有在线会话。
     */
    private void broadcastToRoom(Long roomId, DanmakuPayload payload) {
        DanmakuMessage msg = new DanmakuMessage();
        msg.setType("CHAT");
        msg.setData(payload);

        Map<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(msg);
            // ConcurrentHashMap 的迭代器是【弱一致】的：遍历期间其他线程的增删可能看不到，
            // 也可能看到已删除的元素，但不会抛 ConcurrentModificationException。
            // 这里正好利用这个特性——边遍历边清理死连接（调用方线程自己在同一个 Map 上增删是安全的）。
            for (WebSocketSession s : sessions.values()) {
                if (s == null) continue;

                if (!s.isOpen()) {
                    removeSession(roomId, s);   // 已关闭：顺手清掉，不再尝试发送
                    continue;
                }

                try {
                    // 同一个 WebSocket 上并发 sendMessage 会互相打断（Tomcat 的 WsRemoteEndpoint
                    // 不允许并发写），所以按会话加锁发送
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                } catch (Exception e) {
                    // 单个会话发送失败（网络已断等）不影响其他在线用户
                    log.debug("弹幕发送失败，移除该会话 sessionId={} roomId={}", s.getId(), roomId, e);
                    removeSession(roomId, s);
                }
            }
        } catch (Exception e) {
            // 整体性异常（例如序列化失败），此时没有任何人收到这条弹幕
            log.error("弹幕广播失败 roomId={}", roomId, e);
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
        return toLong(session.getAttributes().get(DanmakuHandshakeInterceptor.ATTR_ROOM_ID));
    }

    /** 从会话里取 userId（由握手拦截器从 Token 解析后放入 attributes） */
    private Long userIdOf(WebSocketSession session) {
        return toLong(session.getAttributes().get(DanmakuHandshakeInterceptor.ATTR_USER_ID));
    }

    /** 从会话里取"服务端查出来的昵称"（前端传什么都覆盖不了它） */
    private String nicknameOf(WebSocketSession session) {
        Object v = session.getAttributes().get(DanmakuHandshakeInterceptor.ATTR_NICKNAME);
        String s = v == null ? null : String.valueOf(v).trim();
        return (s == null || s.isEmpty()) ? "游客" : s;
    }

    /**
     * 这条连接的 Token 是否已经过期。
     *
     * <p>握手时把"Token 过期时刻"算好放进 attributes，这里只做一次比较，
     * 不用每条消息都重新验签（验签是纯 CPU 活）。
     */
    private boolean isTokenExpired(WebSocketSession session) {
        Object v = session.getAttributes().get(DanmakuHandshakeInterceptor.ATTR_EXPIRE_AT);
        if (!(v instanceof Number n)) {
            return false;
        }
        return System.currentTimeMillis() > n.longValue();
    }

    /**
     * 固定窗口限速：同一连接每秒最多 {@value #MAX_MESSAGES_PER_SECOND} 条。
     *
     * @return true = 放行，false = 超限
     */
    private boolean allowSend(WebSocketSession session) {
        long now = System.currentTimeMillis();
        long[] win = sendWindows.computeIfAbsent(session.getId(), k -> new long[]{now, 0});
        synchronized (win) {
            if (now - win[0] >= 1000L) {   // 进入新窗口
                win[0] = now;
                win[1] = 0;
            }
            if (win[1] >= MAX_MESSAGES_PER_SECOND) {
                return false;
            }
            win[1]++;
            return true;
        }
    }

    /** 颜色归一化：只认 #rgb / #rrggbb，其余一律回落到默认色 */
    private String normalizeColor(Object raw) {
        if (raw == null) {
            return DEFAULT_COLOR;
        }
        String c = String.valueOf(raw).trim();
        return COLOR_PATTERN.matcher(c).matches() ? c : DEFAULT_COLOR;
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
