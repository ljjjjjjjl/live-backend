package com.live.controller;

import com.live.common.Result;
import com.live.service.LiveRoomRecommendService;
import com.live.vo.LiveRoomVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 直播间推荐接口。
 *
 * GET /api/live/recommend?userId=1&size=10
 *   - userId：当前用户（带登录用户才能个性化）
 *   - size  ：要几条
 *
 * ⚠️ JWT 说明：第 3 周做完 JWT 后，userId 应改为从 Token 解析，而非前端直接传。
 */
@RestController
@RequestMapping("/api/live/recommend")
public class LiveRoomRecommendController {

    @Autowired
    private LiveRoomRecommendService liveRoomRecommendService;

    /**
     * 获取推荐直播间列表。
     */
    @GetMapping
    public Result<List<LiveRoomVO>> recommend(@RequestParam(required = false) Long userId,
                                              @RequestParam(required = false) Integer size) {
        return Result.ok(liveRoomRecommendService.recommend(userId, size));
    }
}
