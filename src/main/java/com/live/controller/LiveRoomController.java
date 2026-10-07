package com.live.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.live.common.Result;
import com.live.entity.LiveRoom;
import com.live.service.LiveRoomService;
import com.live.vo.LiveRoomVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 直播间接口：列表 / 创建 / 开播 / 关播。
 *
 * 身份来源（⭐ 核心改动）：
 *   - /create、/start、/stop 的操作者身份(userId) 由 JWT 拦截器从 Token 解析后放进 request，
 *     这里用 @RequestAttribute("userId") 取——**不再信任前端传的 anchorId，防止冒充主播。**
 *   - 列表 / 展示类接口不涉及身份，无需 Token。
 *
 * 参数校验：类上 @Validated 让 @RequestParam/@PathVariable 的约束注解(@Min/@Max/@Positive)生效；
 *           create 的 @RequestBody LiveRoom 用 @Valid 触发 @NotBlank/@Size 校验。
 */
@Validated
@RestController
@RequestMapping("/api/live/rooms")
public class LiveRoomController {

    @Autowired
    private LiveRoomService liveRoomService;

    /**
     * 直播间列表（分页）。
     * GET /api/live/rooms?pageNum=1&pageSize=10&category=游戏
     *   - pageNum  第几页（默认 1，≥1）
     *   - pageSize 每页几条（默认 10，1~50）
     *   - category 分区过滤（可空）
     * 返回：data = {records:[直播间VO...], total, current, size}
     */
    @GetMapping
    public Result<IPage<LiveRoomVO>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "pageNum不能小于1") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "pageSize不能小于1")
            @Max(value = 50, message = "pageSize不能大于50") int pageSize,
            @RequestParam(required = false) String category) {
        return liveRoomService.list(pageNum, pageSize, category);
    }

    /**
     * 创建直播间（主播首次开播）。
     * POST /api/live/rooms
     * Body: {"title":"我的第一场直播","category":"游戏","tags":"王者荣耀,教学"}
     * 身份：从 Token 取 userId 作为 anchorId（前端不用再传 anchorId，也传不了真的）
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody LiveRoom room,
                               @RequestAttribute("userId") Long userId) {
        return liveRoomService.create(room, userId);
    }

    /**
     * 开播：把指定直播间设为直播中。
     * POST /api/live/rooms/{id}/start
     * 身份：从 Token 取 userId（不再用 query 传 anchorId）
     */
    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable @Positive(message = "直播间ID必须为正数") Long id,
                              @RequestAttribute("userId") Long userId) {
        return liveRoomService.start(id, userId);
    }

    /**
     * 关播：把指定直播间设为下播。
     * POST /api/live/rooms/{id}/stop
     */
    @PostMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable @Positive(message = "直播间ID必须为正数") Long id,
                             @RequestAttribute("userId") Long userId) {
        return liveRoomService.stop(id, userId);
    }
}
