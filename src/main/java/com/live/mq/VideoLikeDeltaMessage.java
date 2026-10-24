package com.live.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 「视频点赞数变更」的 MQ 消息体。
 *
 * <p><b>为什么传 delta（增量）而不是"最新总数"？</b>
 * 增量可合并、可乱序叠加，最多是"最终一致"；而总数是绝对值，
 * 一旦消息乱序或重复，就会用旧值覆盖新值，把计数写错。
 * 而且增量还能让消费者把一段时间内的多条消息合并成一次写，天然具备削峰能力。
 *
 * <p><b>为什么带 messageId？</b>
 * RocketMQ 是 at-least-once：网络抖动、消费超时、Rebalance 都会导致同一条消息被投递多次。
 * 消费端必须自己实现幂等，否则一次点赞可能被加好几次。
 * 这里用 messageId 做去重键（见 {@code VideoLikeCountConsumer}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoLikeDeltaMessage {

    /** 消息主题：视频点赞数变更 */
    public static final String TOPIC = "video-like-delta";

    /**
     * 消息唯一标识（UUID）。
     * 消费端用它在 Redis 里做 SETNX 去重，保证同一条消息只被应用一次。
     */
    private String messageId;

    /** 被点赞的视频 ID */
    private Long videoId;

    /** 增量：+1 点赞，-1 取消点赞 */
    private long delta;

    /** 事件产生时间（毫秒），便于排查积压与乱序 */
    private long createdAt;
}
