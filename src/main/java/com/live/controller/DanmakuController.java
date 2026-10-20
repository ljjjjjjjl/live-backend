package com.live.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.live.common.Result;
import com.live.entity.Danmaku;
import com.live.service.DanmakuService;
import com.live.service.UserService;
import com.live.service.WsTicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 弹幕 REST 接口：拉历史 + 服务端代发。
 *
 * 说明：
 *   - 实时弹幕走 WebSocket（/ws/danmaku/{roomId}），前端那里发、收。
 *   - 本 Controller 提供三个附加能力：
 *       ① GET  /api/danmaku?roomId=xx         拉历史弹幕（分页，最新在前）——聊天"开屏补历史"用
 *       ② POST /api/danmaku                    服务端代发一条弹幕（如系统播报、后端逻辑触发）
 *       ③ POST /api/danmaku/ws-ticket          换取 WebSocket 握手用的一次性票据
 *          （浏览器的 WS 不能带请求头，用票据代替把 JWT 拼在 URL 上，见该方法注释）
 *
 * 身份来源：userId 一律从 JWT Token 取（@RequestAttribute("userId")），不信前端传的 userId；
 *   弹幕昵称也不采信前端，由 UserService#displayName 从库里查（防止冒充主播/他人）。
 * 参数校验：类上 @Validated 使 @RequestParam 约束生效；send 的 @RequestBody 用 @Valid 触发
 *   @NotNull/@NotBlank/@Size（替代原先手写的判空）。
 */
@Validated
@RestController
@RequestMapping("/api/danmaku")
public class DanmakuController {

    @Autowired
    private DanmakuService danmakuService;

    @Autowired
    private UserService userService;

    @Autowired
    private WsTicketService wsTicketService;

    /**
     * 换取 WebSocket 握手用的一次性票据。
     * POST /api/danmaku/ws-ticket   （需要登录：Token 放在 Authorization 头里）
     *
     * <p>为什么需要它：浏览器的 WebSocket <b>不能带自定义请求头</b>，
     * 所以没法像普通接口那样用 {@code Authorization: Bearer} 认身份。
     * 常见偷懒做法是把 JWT 拼进 URL（{@code ?token=}），但 URL 会进 access log / 代理日志 / Referer。
     *
     * <p>正确做法是"用票据换连接"：这个接口（走普通 HTTP，可以带请求头）签发一张
     * <b>60 秒内有效、只能用一次</b>的随机票据；前端拿它去连
     * {@code ws://host/ws/danmaku/{roomId}?ticket=xxx}，握手时原子消费掉。
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

    /**
     * 拉取某直播间历史弹幕（分页）。
     * GET /api/danmaku?roomId=1&pageNum=1&pageSize=20
     */
    @GetMapping
    public Result<IPage<Danmaku>> history(
            @RequestParam @NotNull(message = "roomId不能为空") Long roomId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "pageNum不能小于1") int pageNum,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize不能小于1")
            @Max(value = 100, message = "pageSize不能大于100") int pageSize) {
        return Result.ok(danmakuService.history(roomId, pageNum, pageSize));
    }

    /**
     * 服务端代发一条弹幕（入库，但不广播给 WebSocket 在线用户）。
     * POST /api/danmaku
     * Body: {"roomId":1,"content":"欢迎来到直播间","color":"#ff0000"}
     *   身份：userId 从 JWT Token 取（@RequestAttribute("userId")），不信前端传的 userId。
     *
     * 入库前校验：roomId @NotNull、content @NotBlank/@Size——由 @Valid 触发，校验失败走
     * GlobalExceptionHandler 返回统一 {code:1,msg,...}。
     *
     * 之所以不在这里广播：广播是 WebSocket 层（有会话表）的职责，
     * REST 与 WebSocket 是两个通道，直接广播会让这一层依赖得死死的。
     * （真实项目往往用 Redis 发布订阅把 REST 产生的事件推给在线会话，周 3 做 Redis 后再来解耦。）
     */
    @PostMapping
    public Result<Danmaku> send(@Valid @RequestBody Danmaku danmaku,
                                @RequestAttribute("userId") Long userId) {
        // 身份来自 Token，覆盖前端传的（防止冒充别人发弹幕）
        danmaku.setUserId(userId);
        // ⚠️ 昵称同样**不再采信前端**：原来只是"空的时候填游客"，于是任何人都能把昵称
        //    写成"主播"或别人的名字，而且会落库、出现在所有人的历史弹幕里。
        //    现在一律用服务端查出来的展示名。
        danmaku.setNickname(userService.displayName(userId));
        danmaku.setCreateTime(java.time.LocalDateTime.now());
        Danmaku saved = danmakuService.save(danmaku);
        return Result.ok(saved);
    }
}
