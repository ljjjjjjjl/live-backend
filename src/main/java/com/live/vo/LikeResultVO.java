package com.live.vo;

import lombok.Data;

/**
 * 点赞操作结果视图对象。
 *
 * <p>为什么单独包一个 VO 而不是直接返回数字？
 * 因为点赞接口要同时告诉前端两件事：
 * <ol>
 *   <li>{@code liked} —— 操作完成后"我到底赞没赞"（点赞/取消点赞是同一个按钮，前端要知道当前该显示什么状态）；</li>
 *   <li>{@code likeCount} —— 操作完成后的<b>最新总数</b>，前端直接拿它刷新数字，不用再发一次查询。</li>
 * </ol>
 * 这样一次请求就把"状态 + 数字"都带回去了，前端零额外请求。
 */
@Data
public class LikeResultVO {

    /** 操作完成后当前用户是否已点赞（true=已赞，false=未赞） */
    private Boolean liked;

    /** 操作完成后的最新点赞总数（来自 Redis 计数，实时值） */
    private Long likeCount;

    public LikeResultVO(Boolean liked, Long likeCount) {
        this.liked = liked;
        this.likeCount = likeCount;
    }
}
