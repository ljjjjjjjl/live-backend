package com.live.task;

import com.live.service.LikeReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 点赞数<b>对账任务</b>：把派生列 {@code video.like_count} 按权威表 {@code user_like} 校正回来。
 *
 * <h2>为什么必须有对账？</h2>
 * 点赞的数据流是"<b>权威同步写 + 派生态异步刷回</b>"：
 * <pre>
 *   点一次赞 ──► user_like 表（同步，绝不丢）
 *            └─► Redis 计数（同步或 MQ 异步，用于展示）
 *            └─► 脏集合标记 → 本任务 UPDATE video.like_count
 * </pre>
 * {@code video.like_count} 这一列<b>没有任何东西实时更新它</b>，
 * 它是"聚合结果"，允许延迟，于是用对账顶上空缺 —— 消息丢了、消费者挂了、服务重启了，
 * 最终都由这里把数据库那一列拉回正确值。
 *
 * <p>对账的定义：只要是"异步/缓存"的数据流，就一定会因为消息丢失、消费者异常、服务重启、
 * 并发覆盖产生偏差，所以必须有一个"<b>以权威源为准，定期把派生数据拉回一致</b>"的兜底任务。
 *
 * <h2>两级对账（2026-10-07 改造：全量 → 脏集合）</h2>
 * <table border="1">
 *   <tr><th></th><th>① 脏集合对账（主力）</th><th>② 全量对账（兜底）</th></tr>
 *   <tr><td>频率</td><td>每 30 秒</td><td>每天凌晨 3 点</td></tr>
 *   <tr><td>处理谁</td><td>这一轮被点过的那批 videoId（SPOP，限批）</td><td>整张 video 表</td></tr>
 *   <tr><td>成本</td><td>几十~几百次主键/索引查询</td><td>两表全扫（所以只敢一天跑一次）</td></tr>
 *   <tr><td>作用</td><td>把偏差在秒级修掉</td><td>Redis 脏集合丢了也不会永久漏修</td></tr>
 * </table>
 *
 * <p>为什么两级都要：脏集合在 Redis 里，<b>Redis 重启/flush 就没了</b> ——
 * 那些"脏了但标记丢了"的视频只有全量对账能兜住。两者是互补，不是替代。
 *
 * <h2>如果 MQ 接入了，这个任务要删吗？</h2>
 * <b>不要删。</b> MQ 保证的是"平时尽快刷回"（秒级），对账保证的是"最终一定正确"（分钟级）。
 */
@Slf4j
@Component
public class LikeCountReconcileTask {

    @Autowired
    private LikeReconcileService likeReconcileService;

    /** 开关：便于压测/排查时临时关掉（默认开） */
    @Value("${app.like.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    /** 脏集合对账每轮最多处理多少个 videoId（限批，防止自己攒成大事务） */
    @Value("${app.like.dirty-batch-size:1000}")
    private int dirtyBatchSize;

    /**
     * ① 脏集合对账（主力）：默认每 30 秒一轮，每轮最多 {@code dirty-batch-size} 个。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是"上一轮跑完再等 N 毫秒"，
     * 后者是"每 N 毫秒启动一轮"。对账耗时不确定，用 fixedDelay 才能保证<b>不重叠</b>。
     */
    @Scheduled(fixedDelayString = "${app.like.dirty-reconcile-interval:30000}")
    public void reconcileDirtyLikes() {
        if (!reconcileEnabled) {
            return;
        }
        try {
            likeReconcileService.reconcileDirty(dirtyBatchSize);
        } catch (Exception e) {
            // 定时任务里的异常必须自己兜住：抛出去只会被调度器吞掉/打断后续调度，问题更难发现
            log.error("【点赞对账·脏集合】执行失败", e);
        }
    }

    /**
     * ② 全量对账（兜底）：默认每天凌晨 3 点跑一次。
     *
     * <p>它扫全表，所以<b>刻意低频</b>；正常情况下一轮改 0 行
     * （SQL 里带了 {@code WHERE like_count <> 真实值}），成本可以接受。
     */
    @Scheduled(cron = "${app.like.full-reconcile-cron:0 0 3 * * ?}")
    public void reconcileAllLikes() {
        if (!reconcileEnabled) {
            return;
        }
        try {
            int fixed = likeReconcileService.reconcileAll();
            if (fixed > 0) {
                log.warn("【点赞对账·全量兜底】发现并修正了 {} 行偏差 —— 说明脏集合漏了东西，建议排查", fixed);
            } else {
                log.info("【点赞对账·全量兜底】全表一致，未修正任何行");
            }
        } catch (Exception e) {
            log.error("【点赞对账·全量兜底】执行失败", e);
        }
    }
}
