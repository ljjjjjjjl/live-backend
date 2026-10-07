package com.live.service;

import com.live.entity.Video;
import com.live.mapper.VideoMapper;
import com.live.vo.LikeCountPair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 点赞计数<b>对账</b>服务：把派生列 {@code video.like_count} 按权威表 {@code user_like} 校正回来。
 *
 * <h2>为什么要"脏集合"对账（2026-10-07 改造）</h2>
 * 原来的做法是<b>每 5 分钟全量对账</b>：一条 {@code UPDATE video LEFT JOIN (SELECT ... FROM user_like GROUP BY video_id)}
 * 把两张表都扫一遍。它有两个问题：
 * <ol>
 *   <li><b>扫描量随数据量线性增长</b>：{@code user_like} 上亿后，每轮全表聚合会冲掉 buffer pool，
 *       把正常查询也拖慢（注意 {@code WHERE like_count <> 真值} 省的是"更新"，不是"扫描"）；</li>
 *   <li><b>整条语句是一个大事务</b>：真有大面积偏差时，长事务 + 大 undo + binlog 暴涨。</li>
 * </ol>
 *
 * <p>改成"脏集合"之后，对账只处理<b>这一轮里被改动过</b>的视频：
 * <pre>
 *   点赞/取消点赞（user_like 变了）
 *        └─ SADD like:dirty:video {videoId}      ← 谁改谁标记，Set 天然去重
 *                    ↓  每 30 秒
 *   对账任务：SPOP 一批（默认 1000）→ 只查这批 videoId 的真实赞数 → 只改对不上的行
 * </pre>
 *
 * <h2>四个刻意的设计点</h2>
 * <ul>
 *   <li><b>Set 天然去重</b>：同一个视频被点 1 万次，脏集合里仍然只有一个成员；</li>
 *   <li><b>用 {@code SPOP} 而不是 {@code SMEMBERS} + {@code SREM}</b>：SPOP 是原子弹出，
 *       "取出来"和"删掉"一步完成，不会出现"取了 1000 个还没删就崩了 → 下一轮重复处理"；</li>
 *   <li><b>限批</b>：一轮最多处理 {@code batchSize} 个，剩下的留到下一轮 ——
 *       避免对账自己攒成一个大事务（和"分页删大表"是同一个道理）；</li>
 *   <li><b>{@code SPOP} 必须在事务外</b>：如果放在 {@code @Transactional} 里，事务一回滚，
 *       弹出来的 videoId 就<b>永久丢了</b>（没有别的地方记得它们脏）——这正是项目里
 *       "Redis 不要待在数据库事务里"那条原则的又一个实例。</li>
 * </ul>
 *
 * <h2>兜底：全量对账不能删</h2>
 * 脏集合存在 Redis 里，<b>Redis 重启/flush 就没了</b>，那些"脏了但标记丢了"的视频会永远没人修。
 * 所以全量对账要保留，只是降频成<b>每天凌晨跑一次</b>当保险（{@code reconcileAll()}）。
 * 面试里主动说出这一句，等于告诉对方"我想过失效场景"。
 */
@Slf4j
@Service
public class LikeReconcileService {

    /**
     * 脏集合 key：值是被改动过点赞、计数<b>可能</b>不一致的 videoId。
     *
     * <p>为什么 key 里带业务前缀 {@code like:} 和目标类型 {@code video}：
     * 沿用视频点赞计数 {@code video:like:count:} 的命名思路 —— 避免"视频 101"和"直播间 101"
     * 这类不同目标撞进同一个 key（{@code CountCacheService} 的 {@code live:count:} 就踩过这个坑）。
     */
    public static final String DIRTY_KEY = "like:dirty:video";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 标记"这个视频的计数可能脏了"。
     *
     * <p>调用时机：<b>任何改动 {@code user_like} 的地方</b>（点赞新增、取消删除），
     * 且必须在数据库事务提交之后调用（和 Redis 计数自增同一位置）。
     * 漏掉任何一个写入口，那个入口造成的偏差就只能等全量对账来修。
     *
     * <p>重复标记没有成本：Set 的成员唯一，同一个 videoId 加一万次还是一个。
     */
    public void markDirty(Long videoId) {
        if (videoId == null) {
            return;
        }
        stringRedisTemplate.opsForSet().add(DIRTY_KEY, String.valueOf(videoId));
    }

    /**
     * 脏集合对账：处理一批，返回真正被修正的行数。
     *
     * @param batchSize 本轮最多处理多少个 videoId（限批，避免自造大事务）
     */
    public int reconcileDirty(int batchSize) {
        // ① Redis 操作放事务外：SPOP 弹出来的就是"本轮要负责的"，事务回滚也不会把它们弄丢
        List<String> popped = stringRedisTemplate.opsForSet().pop(DIRTY_KEY, batchSize);
        if (popped == null || popped.isEmpty()) {
            return 0;
        }
        Set<Long> ids = popped.stream()
                .map(Long::valueOf)
                .collect(Collectors.toCollection(HashSet::new));

        // ② 读 + 写放进一个短事务（范围只是"这一批脏视频"，不是全表）
        Integer fixed = transactionTemplate.execute(status -> fixOneBatch(ids));
        int result = fixed == null ? 0 : fixed;
        if (result > 0) {
            log.info("【点赞对账·脏集合】本轮处理 {} 个脏视频，修正 {} 行的 like_count", ids.size(), result);
        } else {
            log.debug("【点赞对账·脏集合】本轮处理 {} 个脏视频，数据一致，未修改任何行", ids.size());
        }
        return result;
    }

    /** 在事务里：查真实值 → 比对 → 只改对不上的行 */
    private int fixOneBatch(Set<Long> ids) {
        List<Long> idList = List.copyOf(ids);

        // ① 真实赞数：WHERE video_id IN (...) 走 idx_video 范围扫描（查不到 = 0 个赞，用 getOrDefault 兜住）
        Map<Long, Long> realCounts = videoMapper.selectRealLikeCounts(idList).stream()
                .collect(Collectors.toMap(LikeCountPair::getVideoId, LikeCountPair::getCnt, (a, b) -> a));

        // ② 当前派生值：一次主键 IN 查询拿回来
        Map<Long, Long> currentCounts = videoMapper.selectBatchIds(idList).stream()
                .collect(Collectors.toMap(Video::getId, v -> v.getLikeCount() == null ? 0L : v.getLikeCount()));

        // ③ 只在 Java 里比对，只更新有差异的行（稳态下通常 0 行）
        int fixed = 0;
        for (Long videoId : idList) {
            long real = realCounts.getOrDefault(videoId, 0L);
            long current = currentCounts.getOrDefault(videoId, 0L);
            if (real != current) {
                fixed += videoMapper.updateLikeCountIfDiff(videoId, real);
            }
        }
        return fixed;
    }

    /**
     * 全量对账（<b>兜底</b>）：以权威表为准，把整张 {@code video} 的派生列拉回一致。
     *
     * <p>正常情况下它一轮改 0 行（{@code WHERE like_count <> 真值}），所以每天跑一次的成本可以接受；
     * 它的价值在于"Redis 脏集合丢了也不会永久漏修"。
     */
    public int reconcileAll() {
        return videoMapper.reconcileLikeCount();
    }

    /** 仅用于排查/演示：当前积压了多少个待对账的脏视频 */
    public long dirtyBacklog() {
        Long size = stringRedisTemplate.opsForSet().size(DIRTY_KEY);
        return size == null ? 0L : size;
    }
}
