package com.live.mq;

import com.live.service.LikeReconcileService;
import com.live.service.VideoLikeCountStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 视频点赞增量的消费者：把 Redis 计数和"脏标记"更新掉。
 *
 * <h2>它只做两件事</h2>
 * <ol>
 *   <li>{@code applyDelta} —— 原子地把增量应用到 {@code video:like:count:{videoId}}；</li>
 *   <li>{@code markDirty} —— 把这个视频标进脏集合，交给定时对账修正 {@code video.like_count}。</li>
 * </ol>
 *
 * <h2>★ 幂等：为什么必须有</h2>
 * <p>RocketMQ 保证的是 <b>at-least-once（至少一次）</b>，不是 exactly-once。
 * 下面这些情况都会让同一条消息被消费多次：
 * <ul>
 *   <li>消费成功但返回 ACK 的瞬间网络断了 → Broker 认为没消费成功，重投；</li>
 *   <li>消费超时 → 重投；</li>
 *   <li>消费者扩缩容触发 Rebalance → 队列被重新分配，部分消息重投。</li>
 * </ul>
 * 一次点赞被消费两次，计数就多加 1 —— 而且这种错<b>不会自愈</b>，因为对账只校正
 * {@code video.like_count} 那一列（重新 COUNT），并不校正 Redis 计数。
 *
 * <p>所以这里用 Redis 的 <b>SETNX 做去重</b>：以 messageId 为键，第一次设置成功才继续执行，
 * 第二次直接跳过。TTL 取 30 分钟 —— 覆盖 Broker 的重投窗口即可，
 * 太久会白占内存，太短又可能在重投到达前就过期。
 *
 * <h2>为什么是 ORDERLY 消费</h2>
 * <p>生产端用 {@code videoId} 做 sharding key（见 {@code RocketLikeCountPublisher}），
 * 这里就必须声明 {@link ConsumeMode#ORDERLY}，否则同一个队列里的消息仍可能被并发分发到多个线程，
 * 顺序保证就白设了。两者必须成对出现，缺一个都等于没做。
 *
 * <h2>消费失败会怎样</h2>
 * <p>直接抛异常即可：RocketMQ 会自动重试（默认 16 次，退避递增），
 * 超过次数后进入死信队列 {@code %DLQ%video-like-delta-consumer} 供人工排查。
 * 同时因为权威表 {@code user_like} 早就提交了，即使消息彻底丢了，
 * 每日全量对账也能把 {@code video.like_count} 拉回正确值 —— 这就是"派生数据可重算"的底气。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = VideoLikeDeltaMessage.TOPIC,
        consumerGroup = "video-like-delta-consumer",
        consumeMode = ConsumeMode.ORDERLY)
public class VideoLikeCountConsumer implements RocketMQListener<VideoLikeDeltaMessage> {

    /** 幂等去重键前缀：mq:dedup:video-like:{messageId} */
    private static final String DEDUP_KEY_PREFIX = "mq:dedup:video-like:";

    /** 去重键存活时间：覆盖 Broker 的重投窗口即可 */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(30);

    @Autowired
    private VideoLikeCountStore videoLikeCountStore;

    @Autowired
    private LikeReconcileService likeReconcileService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(VideoLikeDeltaMessage message) {
        if (message == null || message.getVideoId() == null || message.getMessageId() == null) {
            // 格式不对的消息重试多少次都不会成功，直接丢弃（打日志留证）
            log.warn("【MQ】收到无法处理的点赞增量消息，已丢弃：{}", message);
            return;
        }

        String dedupKey = DEDUP_KEY_PREFIX + message.getMessageId();
        Boolean firstTime = stringRedisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL);

        if (!Boolean.TRUE.equals(firstTime)) {
            // 已经处理过 —— 这是重投，直接跳过。注意这里必须 return 而不是抛异常，
            // 抛异常会让 Broker 继续重投，陷入死循环。
            log.debug("【MQ】重复消息已忽略：messageId={}, videoId={}",
                    message.getMessageId(), message.getVideoId());
            return;
        }

        videoLikeCountStore.applyDelta(message.getVideoId(), message.getDelta());
        likeReconcileService.markDirty(message.getVideoId());

        log.debug("【MQ】已消费点赞增量：videoId={}, delta={}, messageId={}",
                message.getVideoId(), message.getDelta(), message.getMessageId());
    }
}
