package com.live.vo;

import lombok.Data;

/**
 * 「视频 → 真实点赞数」的投影（对账专用）。
 *
 * <p>对账时只需要两列：视频 ID 和它在权威表 {@code user_like} 里的真实条数，
 * 用一个轻量对象接住 {@code SELECT video_id, COUNT(*) FROM user_like WHERE video_id IN (...) GROUP BY video_id}
 * 的结果，避免为了一个计数去查整行 {@code user_like}（那样要回表）。
 */
@Data
public class LikeCountPair {

    /** 视频 ID */
    private Long videoId;

    /** 该视频在 user_like 表里的真实点赞条数 */
    private Long cnt;
}
