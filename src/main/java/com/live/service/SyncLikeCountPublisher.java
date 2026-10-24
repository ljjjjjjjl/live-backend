package com.live.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 点赞增量的<b>同步直算</b>实现 —— MQ 关闭时的默认行为（{@code app.mq.enabled=false}）。
 *
 * <p>它看起来"什么都没做"，其实做的是<b>保持旧行为</b>：把增量立刻应用到 Redis 计数上，
 * 并把视频标脏交给对账。也就是说，关闭 MQ 时整套系统的行为与接入 MQ 之前<b>完全一致</b>
 * （接口返回的点赞数会立即变化）。
 *
 * <h2>为什么用 @ConditionalOnProperty 而不是 @Primary</h2>
 * 容器里同时存在两个 {@link LikeCountPublisher} 实现时，Spring 不知道注入哪个，会抛
 * {@code NoUniqueBeanDefinitionException}。{@code @Primary} 虽然能解决注入问题，
 * 但**两个 bean 仍然都会被创建**（对 MQ 实现来说意味着消费者照样启动、照样连 NameServer）。
 * 用 {@code @ConditionalOnProperty} 则是直接在条件不满足时<b>不创建这个 bean</b>，
 * 更干净，也符合 Spring Boot 3 的推荐写法。
 *
 * <h2>与异步实现的分工</h2>
 * <table border="1">
 *   <tr><th>配置</th><th>实现</th><th>增量什么时候落 Redis</th><th>接口返回的计数</th></tr>
 *   <tr><td>{@code app.mq.enabled=false}</td><td>本类</td><td>请求线程内，同步</td><td>立刻正确</td></tr>
 *   <tr><td>{@code app.mq.enabled=true}</td><td>{@link RocketLikeCountPublisher}</td>
 *       <td>消费线程，异步</td><td>可能滞后几毫秒（最终一致）</td></tr>
 * </table>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "false", matchIfMissing = true)
public class SyncLikeCountPublisher implements LikeCountPublisher {

    @Autowired
    private VideoLikeCountStore videoLikeCountStore;

    @Autowired
    private LikeReconcileService likeReconcileService;

    @Override
    public void publishVideoLikeDelta(Long videoId, long delta) {
        // ① 立刻把增量应用到 Redis 计数（原子 Lua）
        videoLikeCountStore.applyDelta(videoId, delta);

        // ② 标脏：video.like_count 现在和权威表对不上了，交给定时对账定点修正。
        //    即使这里、或上面的 Redis 操作失败了，对账也能把数据库那一列拉回正确值。
        likeReconcileService.markDirty(videoId);
    }
}
