package com.live.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 握手用的<b>一次性票据</b>服务。
 *
 * <h2>为什么需要它（这是本轮"JWT 不要放在 URL 里"那条的落地方式）</h2>
 * 浏览器的 WebSocket 构造函数<b>不能自定义请求头</b>，所以常见做法是把 Token 拼在
 * {@code ws://host/ws/danmaku/1?token=<JWT>} 上 —— 但 URL 会被写进：
 * <ul>
 *   <li>Nginx / Tomcat 的 access log；</li>
 *   <li>反向代理、APM、网关的请求记录；</li>
 *   <li>出错时的浏览器 Referer、截图、工单。</li>
 * </ul>
 * 等于把"有效期 2 小时的登录凭证"撒到一堆日志里。
 *
 * <p>正确做法是<b>用票据换连接</b>：
 * <pre>
 *   ① 页面先用 Authorization 头调 POST /api/danmaku/ws-ticket
 *        → 服务端生成随机 ticket，Redis 里存 ws:ticket:{ticket} = userId，TTL 60 秒
 *   ② 页面连 ws://host/ws/danmaku/1?ticket=xxx
 *        → 握手拦截器 GET + DEL（Lua 原子）消费掉它，取出 userId
 * </pre>
 * 这样即使 URL 被记进日志，泄漏的也只是一张 <b>60 秒内、且只能用一次</b>的废纸。
 *
 * <p>"GET + DEL 必须原子"的理由和短信验证码完全一样：分两步做，
 * 两个并发握手可以同时读到同一张票据 → 一票多用。
 */
@Service
public class WsTicketService {

    private static final String KEY_PREFIX = "ws:ticket:";

    /** 票据有效期（秒）：够"拿到票据 → 立刻握手"这一来一回，越短越安全 */
    public static final long TICKET_TTL_SECONDS = 60L;

    /**
     * 原子"取出并删除"票据。
     * <pre>KEYS[1] = ws:ticket:{ticket}  返回 userId 字符串，不存在返回 nil</pre>
     */
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return v
            """, String.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 签发一张票据（只有服务端能签，绑定了已登录的 userId） */
    public String issue(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue()
                .set(KEY_PREFIX + ticket, String.valueOf(userId), TICKET_TTL_SECONDS, TimeUnit.SECONDS);
        return ticket;
    }

    /**
     * 消费票据（一次性）：成功返回 userId，失败/过期/已用过返回 null。
     *
     * <p>失败返回 null 而不是抛异常：握手阶段拿不到身份就是"拒绝连接"，
     * 让拦截器统一处理返回 false。
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.trim().isEmpty()) {
            return null;
        }
        String value = stringRedisTemplate.execute(CONSUME_SCRIPT, List.of(KEY_PREFIX + ticket.trim()));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
