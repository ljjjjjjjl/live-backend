package com.live.controller;

import com.live.common.Result;
import com.live.service.WsTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 弹幕 REST 接口。
 *
 * <h2>现在只剩一个接口（本轮改造）</h2>
 * 弹幕本身已经<b>不再落库</b>（见 {@code docs/plan/danmaku-no-persist.md}），
 * 于是原先挂在这里的两个接口失去了存在基础，已一并删除：
 * <ul>
 *   <li>{@code GET /api/danmaku}（历史分页）—— 数据源没了；留着只会返回
 *       越来越旧的历史行，比删掉更误导调用方。</li>
 *   <li>{@code POST /api/danmaku}（服务端代发）—— 它<b>只落库、不广播</b>，
 *       在线用户本来就看不到；落库取消后它彻底没有作用。</li>
 * </ul>
 *
 * <p>⚠️ 这意味着<b>不再有"服务端代发弹幕"的能力</b>。将来如果要恢复
 * （例如系统播报、主播公告），不要在这个 Controller 里直接去摸 WebSocket 的会话容器
 * —— 那正是原作者刻意避免的耦合方向。正确做法是抽一个
 * {@code DanmakuBroadcaster} 接口由 WebSocket 层实现，Controller 只依赖接口。
 *
 * <h2>保留项目：{@code POST /api/danmaku/ws-ticket}</h2>
 * 它和弹幕内容无关，是 WebSocket 握手的<b>凭据</b>：浏览器的 WebSocket 不能带
 * 自定义请求头，所以没法像普通接口那样用 {@code Authorization: Bearer} 认身份。
 * 常见偷懒做法是把 JWT 拼进 URL（{@code ?token=}），但 URL 会进 access log /
 * 代理日志 / Referer；正规做法就是这里签发的「60 秒、一次性」票据。
 *
 * <p>注意：本类仍挂在 {@code /api/danmaku} 前缀下，所以它<b>依然需要登录</b>
 * （见 {@code WebConfig} 把 {@code /api/danmaku/**} 纳入 JWT 拦截）。
 */
@RestController
@RequestMapping("/api/danmaku")
public class DanmakuController {

    @Autowired
    private WsTicketService wsTicketService;

    /**
     * 换取 WebSocket 握手用的一次性票据。
     * POST /api/danmaku/ws-ticket   （需要登录：Token 放在 Authorization 头里）
     *
     * <p>前端拿它去连 {@code ws://host/ws/danmaku/{roomId}?ticket=xxx}，握手时原子消费掉。
     * 即使 URL 进了日志，泄漏的也只是一张已经作废的票。
     *
     * @return data = { ticket, expireSeconds }
     */
    @PostMapping("/ws-ticket")
    public Result<Map<String, Object>> wsTicket(@RequestAttribute("userId") Long userId) {
        String ticket = wsTicketService.issue(userId);
        return Result.ok(Map.of(
                "ticket", ticket,
                "expireSeconds", WsTicketService.TICKET_TTL_SECONDS
        ));
    }
}
