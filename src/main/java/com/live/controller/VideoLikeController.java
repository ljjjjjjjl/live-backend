package com.live.controller;

import com.live.common.Result;
import com.live.service.VideoLikeService;
import com.live.vo.LikeResultVO;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 视频点赞接口。
 *
 * <ul>
 *   <li>{@code POST   /api/video/{videoId}/like} —— 点赞（幂等）</li>
 *   <li>{@code DELETE /api/video/{videoId}/like} —— 取消点赞（幂等）</li>
 *   <li>{@code GET    /api/video/{videoId}/like} —— 查询点赞状态 + 最新总数</li>
 *   <li>{@code GET    /api/video/liked-ids}      —— 我点过赞的所有视频 ID（列表页批量标记用）</li>
 * </ul>
 *
 * <p><b>为什么用 POST/DELETE 同一个路径，而不是 /like 和 /unlike 两个路径？</b>
 * 因为"点赞"本质上是对"点赞关系"这个<b>资源</b>的创建与删除，
 * 用 HTTP 方法（POST=创建，DELETE=删除）表达动作才符合 REST 语义，
 * 路径保持不变，语义更清晰，也不用为每个动作另造动词。
 *
 * <p><b>身份来源</b>：userId 一律从 JWT Token 解析（拦截器放进 request），
 * <b>绝不接受前端传 userId</b> —— 否则任何人都能伪造"别人给我点赞"。
 *
 * <p><b>与 /api/count 的区别</b>：{@code /api/count} 是早期的无脑计数器演示（谁都能调、不判重、不落库）；
 * 这里的接口才是<b>有身份、有幂等、有权威落库</b>的正式实现。
 */
@Validated
@RestController
@RequestMapping("/api/video")
public class VideoLikeController {

    @Autowired
    private VideoLikeService videoLikeService;

    /** 点赞（重复点赞幂等，不会重复计数） */
    @PostMapping("/{videoId}/like")
    public Result<LikeResultVO> like(
            @PathVariable @Positive(message = "视频ID必须为正数") Long videoId,
            @RequestAttribute("userId") Long userId) {
        return Result.ok(videoLikeService.like(userId, videoId));
    }

    /** 取消点赞（未点赞时调用也幂等，不会把计数减成负数） */
    @DeleteMapping("/{videoId}/like")
    public Result<LikeResultVO> unlike(
            @PathVariable @Positive(message = "视频ID必须为正数") Long videoId,
            @RequestAttribute("userId") Long userId) {
        return Result.ok(videoLikeService.unlike(userId, videoId));
    }

    /** 查询"我赞没赞 + 现在总共多少赞"（进入视频详情时调一次即可） */
    @GetMapping("/{videoId}/like")
    public Result<LikeResultVO> status(
            @PathVariable @Positive(message = "视频ID必须为正数") Long videoId,
            @RequestAttribute("userId") Long userId) {
        boolean liked = videoLikeService.isLiked(userId, videoId);
        return Result.ok(new LikeResultVO(liked, videoLikeService.getLikeCount(videoId)));
    }

    /**
     * 我点过赞的视频 ID 列表。
     *
     * <p>用途：推荐流一次返回 20 条视频，前端不能对每条都发一次"我赞没赞"的请求（20 次往返），
     * 而应该<b>一次性拿到全量已赞 ID</b>，在前端做一次匹配即可。这是列表页的常见优化手法。
     */
    @GetMapping("/liked-ids")
    public Result<List<Long>> likedIds(@RequestAttribute("userId") Long userId) {
        return Result.ok(videoLikeService.likedVideoIds(userId));
    }
}
