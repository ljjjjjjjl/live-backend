package com.live.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.live.common.Result;
import com.live.entity.Danmaku;
import com.live.service.DanmakuService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 弹幕 REST 接口：拉历史 + 服务端代发。
 *
 * 说明：
 *   - 实时弹幕走 WebSocket（/ws/danmaku/{roomId}），前端那里发、收。
 *   - 本 Controller 提供两个附加能力：
 *       ① GET  /api/danmaku?roomId=xx  拉历史弹幕（分页，最新在前）——聊天"开屏补历史"用
 *       ② POST /api/danmaku             服务端代发一条弹幕（如系统播报、后端逻辑触发）
 *
 * 身份来源：POST /api/danmaku 的 userId 从 JWT Token 取（@RequestAttribute("userId")），
 *   不再信前端传的 userId（第 3 周 JWT 已实现）。
 * 参数校验：类上 @Validated 使 @RequestParam 约束生效；send 的 @RequestBody 用 @Valid 触发
 *   @NotNull/@NotBlank/@Size（替代原先手写的判空）。
 */
@Validated
@RestController
@RequestMapping("/api/danmaku")
public class DanmakuController {

    @Autowired
    private DanmakuService danmakuService;

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
        // 昵称为空给个默认值（业务默认，非参数校验）
        if (danmaku.getNickname() == null || danmaku.getNickname().trim().isEmpty()) {
            danmaku.setNickname("游客");
        }
        danmaku.setCreateTime(java.time.LocalDateTime.now());
        Danmaku saved = danmakuService.save(danmaku);
        return Result.ok(saved);
    }
}
