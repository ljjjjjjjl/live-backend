package com.live.controller;

import com.live.common.Result;
import com.live.service.RecommendService;
import com.live.vo.VideoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 视频推荐接口。
 *
 * GET /api/recommend?userId=1&size=10
 *   - userId：当前用户（首页信息流推荐时，带上登录用户才能"千人千面"）
 *   - size  ：要几条（默认 20）
 *
 * ⚠️ JWT 说明：第 3 周做完 JWT 后，这里应该改成从请求头解析 userId，
 *    而不是让前端直接传 userId（否则别人可以伪造。现在是为了先演示推荐效果）。
 *    替换逻辑很简单：把 @RequestParam Long userId 换成
 *    @LoginUser Long userId（你自己的注解，从 Token 里拿）。
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    @Autowired
    private RecommendService recommendService;

    /**
     * 获取推荐视频列表。
     */
    @GetMapping
    public Result<List<VideoVO>> recommend(@RequestParam(required = false) Long userId,
                                           @RequestParam(required = false) Integer size) {
        return Result.ok(recommendService.recommend(userId, size));
    }
}
