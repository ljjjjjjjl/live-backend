package com.live.service;

import com.live.mq.VideoLikeDeltaMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 点赞增量的<b>RocketMQ 异步投递</b>实现 —— {@code app.mq.enabled=true} 时生效。
 *
 * <p>启用后，请求线程里只做"写权威表"，然后<b>发一条消息就走</b>；
 * 真正的 Redis 计数更新和标脏由 {@code VideoLikeCountConsumer} 在消费线程里做。
 *
 * <h2>为什么要发【顺序消息】</h2>
 * <p>用 {@code videoId} 当 sharding key（{@code syncSendOrderly} 的第三个参数），
 * 保证<b>同一个视频</b>的消息全部落到同一条队列、被同一个消费线程按发送顺序处理。
 *
 * <p>严格来说，计数是加减法、可交换，即使乱序最终结论也一样（3-1+5 与 3+5-1 相同）。
 * 那为什么还要顺序？两个真实理由：
 * <ol>
 *   <li><b>排障可读</b>：乱序时会看到 Redis 计数在中途出现"不该出现"的中间值，
 *       排查时无法判断是 bug 还是正常顺序；</li>
 *   <li><b>为合并/对账留路</b>：以后若改成"每条消息带快照"或加"到达即校正"的逻辑，
 *       顺序就是必须的前提。现在就把顺序定下来，后续演进不用改契约。</li>
 * </ol>
 *
 * <h2>发送失败怎么办（降级）</h2>
 * <p>MQ 不可用时<b>不能让用户的点赞失败</b>：权威表已经提交了，事实没有丢。
 * 所以这里捕获异常并<b>降级为同步直算</b>（直接调 Store），保证 Redis 计数不掉队；
 * 即使连降级也失败，还有定时对账兜底。三层保证，任何一层挂掉都不会让数据永久错。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class RocketLikeCountPublisher implements LikeCountPublisher {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private VideoLikeCountStore videoLikeCountStore;

    @Autowired
    private LikeReconcileService likeReconcileService;

    @Override
    public void publishVideoLikeDelta(Long videoId, long delta) {
        VideoLikeDeltaMessage message = new VideoLikeDeltaMessage(
                UUID.randomUUID().toString(),
                videoId,
                delta,
                System.currentTimeMillis());

        try {
            // 第三个参数是 sharding key：同一个视频永远进同一条队列，保证顺序
            rocketMQTemplate.syncSendOrderly(
                    VideoLikeDeltaMessage.TOPIC, message, String.valueOf(videoId));
            log.debug("【MQ】点赞增量已投递：videoId={}, delta={}, messageId={}",
                    videoId, delta, message.getMessageId());
        } catch (Exception e) {
            // 发出去了吗？不确定。但无论发没发出去，"降级同步执行一次"都是安全的：
            //   · 若消息其实已投递成功 → 消费端会用 messageId 去重，不会重复加
            //   · 若确实没投递成功 → 这次同步执行正好补上
            // 这就是"幂等键"带来的好处：失败路径不需要精确知道结果。
            log.error("【MQ】点赞增量投递失败，降级为同步直算：videoId={}, delta={}", videoId, delta, e);
            try {
                videoLikeCountStore.applyDelta(videoId, delta);
                likeReconcileService.markDirty(videoId);
            } catch (Exception fallbackError) {
                // 连降级都失败：至少权威表是对的，交给定时对账把 like_count 校正回来
                log.error("【MQ】降级同步执行也失败，只能依赖定时对账兜底：videoId={}", videoId, fallbackError);
            }
        }
    }
}
