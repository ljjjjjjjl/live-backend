package com.live.task;

import com.live.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 点赞数<b>对账任务</b>：定时把 {@code video.like_count} 按权威表 {@code user_like} 校正回来。
 *
 * <h2>为什么必须有这个任务？</h2>
 * 点赞的数据流是"<b>权威同步写 + 派生态异步刷回</b>"：
 * <pre>
 *   点一次赞 ──► user_like 表（同步，绝不丢）
 *            └─► Redis 计数（同步，用于展示）
 *            └─► MQ 消息（异步）──► 消费者 UPDATE video.like_count   ← 这一步还没实现
 * </pre>
 * 因为 MQ 还没接入（{@code NoopLikeCountPublisher} 是空实现），
 * {@code video.like_count} 目前<b>没有任何东西去更新它</b>，会一直停在旧值。
 * 于是用这个任务顶上空缺。
 *
 * <h2>对账（reconcile）是什么？</h2>
 * 只要是"异步/缓存"的数据流，就一定会因为<b>消息丢失、消费者异常、服务重启、并发覆盖</b>等原因产生偏差。
 * 所以任何异步方案都必须配一个"<b>以权威源为准，定期把派生数据拉回一致</b>"的兜底任务 —— 这叫对账。
 * 面试里只要提一句"我做了定时对账兜底"，就能和"只会用缓存的"候选人区分开。
 *
 * <h2>设计细节</h2>
 * <ul>
 *   <li>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是"上一轮跑完再等 N 毫秒"，
 *       后者是"每 N 毫秒启动一轮"。对账耗时不确定，用 fixedDelay 才能保证<b>不会任务重叠</b>。</li>
 *   <li>SQL 里带了 {@code WHERE like_count <> 真实值}，覆盖数据一致时影响行数为 0，
 *       所以<b>频繁跑也几乎不产生开销</b>（不会白白刷 binlog）。</li>
 *   <li>{@code initialDelay} 让应用启动后先稳一会儿再跑，避免和启动期的其它初始化抢资源。</li>
 * </ul>
 *
 * <h2>与 MQ 的关系</h2>
 * 等 MQ 接入后，这个任务<b>不要删</b>。MQ 保证的是"平时尽快刷回"（秒级），
 * 对账保证的是"最终一定正确"（分钟级）。两者是<b>互补</b>关系，不是替代关系。
 */
@Slf4j
@Component
public class LikeCountReconcileTask {

    @Autowired
    private VideoMapper videoMapper;

    /** 开关：便于压测/排查时临时关掉（默认开） */
    @Value("${app.like.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    /**
     * 定时校正 video.like_count。
     *
     * <p>默认每 5 分钟跑一次（300000 ms），应用启动 1 分钟后开始第一次。
     */
    @Scheduled(fixedDelayString = "${app.like.reconcile-interval:300000}",
            initialDelayString = "${app.like.reconcile-initial-delay:60000}")
    public void reconcileVideoLikeCount() {
        if (!reconcileEnabled) {
            return;
        }
        try {
            int fixed = videoMapper.reconcileLikeCount();
            if (fixed > 0) {
                log.info("【点赞对账】已把 {} 个视频的 like_count 校正为 user_like 的真实计数", fixed);
            } else {
                log.debug("【点赞对账】like_count 与 user_like 一致，无需修正");
            }
        } catch (Exception e) {
            // 定时任务里异常必须自己兜住：抛出去只会被调度器吞掉/打断后续调度，问题还更难发现
            log.error("【点赞对账】执行失败", e);
        }
    }
}
