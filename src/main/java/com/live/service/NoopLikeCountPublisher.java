package com.live.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link LikeCountPublisher} 的<b>空实现（占位）</b>：MQ 还没接入时的默认实现。
 *
 * <p>它什么都不做，只打一行 debug 日志，把"本该投递到 MQ 的消息"记录下来。
 *
 * <p>那数据会丢吗？<b>不会。</b> 因为：
 * <ol>
 *   <li>权威数据 {@code user_like} 已经同步落库了，事实没有丢；</li>
 *   <li>{@code video.like_count} 只是派生值，由 {@code LikeCountReconcileTask} 定时重算兜底。</li>
 * </ol>
 * 所以这里"不投递"只会让 {@code video.like_count} <b>延迟</b>几分钟才正确，而不是错。
 *
 * <p><b>以后接入 RabbitMQ 怎么做？</b>
 * 新写一个 {@code RabbitLikeCountPublisher implements LikeCountPublisher}，
 * 类上加 {@code @Primary}（或 {@code @ConditionalOnProperty} 按配置开关切换）。
 * <b>不要</b>在这里加 {@code @ConditionalOnMissingBean} —— 该注解只在自动配置类里可靠，
 * 用在普通 {@code @Component} 上会受扫描顺序影响，行为不确定。
 */
@Slf4j
@Component
public class NoopLikeCountPublisher implements LikeCountPublisher {

    @Override
    public void publishVideoLikeDelta(Long videoId, long delta) {
        log.debug("【MQ 占位实现】视频 {} 的点赞增量 {} 未投递（RabbitMQ 尚未接入，"
                + "video.like_count 由定时对账任务兜底刷回）", videoId, delta);
    }
}
