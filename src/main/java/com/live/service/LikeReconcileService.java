package com.live.service;

import com.live.entity.Video;
import com.live.mapper.VideoMapper;
import com.live.vo.LikeCountPair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 点赞计数<b>对账</b>服务：把派生列 {@code video.like_count}（以及 Redis 展示值）
 * 按权威表 {@code user_like} 校正回来。
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
 *   对账任务：取一批（默认 1000）→ 只查这批 videoId 的真实赞数 → 只改对不上的行
 * </pre>
 *
 * <h2>四个刻意的设计点</h2>
 * <ul>
 *   <li><b>Set 天然去重</b>：同一个视频被点 1 万次，脏集合里仍然只有一个成员；</li>
 *   <li><b>取批 + 处理成功后再删标记</b>（2026-10-10 修复）：原来的写法是 {@code SPOP}
 *       —— "取出来"和"删掉"一步完成，看着更简洁，但它有一个致命副作用：
 *       <b>标记在数据库操作之前就没了</b>。只要这一批东西随后失败（DB 抖一下、进程重启、
 *       事务回滚），这些 videoId 就<b>永久丢了</b>，只能等凌晨的全量对账兜住。
 *       现在改成"非破坏性取样 → 处理 → 成功才 SREM"，失败就留在集合里下一轮重试；</li>
 *   <li><b>限批</b>：一轮最多处理 {@code batchSize} 个，剩下的留到下一轮 ——
 *       避免对账自己攒成一个大事务（和"分页删大表"是同一个道理）；</li>
 *   <li><b>Redis 操作在事务外</b>：取样和删标记都不进事务；
 *       事务只包"读真值 + 改派生列"那几条 SQL。</li>
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
     * 这类不同目标撞进同一个 key。
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
     * 且必须在数据库事务提交之后调用。漏掉任何一个写入口，
     * 那个入口造成的偏差就只能等全量对账来修。
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
     * <p>流程（顺序很重要）：
     * <ol>
     *   <li><b>非破坏性</b>取一批成员（{@code SRANDMEMBER}，不会把它们从集合里删掉）；</li>
     *   <li>过滤非法成员（Redis 里混进脏数据时不能把整个任务搞崩）；</li>
     *   <li>在<b>短事务</b>里读真值 + 只改对不上的行；</li>
     *   <li>事务提交后：把修正过的视频同步进 Redis 计数缓存（修库不修缓存 = 用户看到的还是错的）；</li>
     *   <li>最后才把这批成员从脏集合里移除 —— 前面任何一步失败，标记都还在，下一轮会重试。</li>
     * </ol>
     *
     * @param batchSize 本轮最多处理多少个 videoId（限批，避免自造大事务）
     */
    public int reconcileDirty(int batchSize) {
        Set<String> members = stringRedisTemplate.opsForSet().distinctRandomMembers(DIRTY_KEY, batchSize);
        if (members == null || members.isEmpty()) {
            return 0;
        }

        // ① 拆分：合法 id / 非法成员（非法成员直接清掉，否则每轮都会踩同一个坑）
        Set<Long> ids = new HashSet<>();
        List<String> handled = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String m : members) {
            try {
                ids.add(Long.valueOf(m));
                handled.add(m);
            } catch (NumberFormatException e) {
                invalid.add(m);
            }
        }
        if (!invalid.isEmpty()) {
            stringRedisTemplate.opsForSet().remove(DIRTY_KEY, invalid.toArray());
            log.warn("【点赞对账·脏集合】清理了 {} 个非法的脏成员: {}", invalid.size(), invalid);
        }
        if (ids.isEmpty()) {
            return 0;
        }

        // ② 读 + 写放进一个短事务（范围只是"这一批脏视频"，不是全表）
        Map<Long, Long> fixedMap = transactionTemplate.execute(status -> fixOneBatch(ids));
        Map<Long, Long> fixed = fixedMap == null ? Map.of() : fixedMap;

        // ③ 事务外：把修正结果同步到 Redis 展示值（对账的意义就是"让用户看到的数字也是对的"）
        //    注意这里**故意不注入 VideoLikeService**：VideoLikeService 依赖本类做 markDirty，
        //    互相注入会形成循环依赖（Spring Boot 3 默认直接启动失败）。
        //    所以只用它的静态方法拿 key 名，Redis 写入这一行自己做。
        fixed.forEach((videoId, real) -> stringRedisTemplate.opsForValue()
                .set(VideoLikeService.countKey(videoId), String.valueOf(real)));

        // ④ 成功处理完才移除标记（失败则留在集合里，下一轮重试）
        stringRedisTemplate.opsForSet().remove(DIRTY_KEY, handled.toArray());

        if (!fixed.isEmpty()) {
            log.info("【点赞对账·脏集合】本轮处理 {} 个脏视频，修正 {} 行的 like_count（并同步 Redis）",
                    ids.size(), fixed.size());
        } else {
            log.debug("【点赞对账·脏集合】本轮处理 {} 个脏视频，数据一致，未修改任何行", ids.size());
        }
        return fixed.size();
    }

    /**
     * 在事务里：查真实值 → 比对 → 只改对不上的行。
     *
     * @return 被修正的 videoId → 权威真值（供事务外同步 Redis）
     */
    private Map<Long, Long> fixOneBatch(Set<Long> ids) {
        List<Long> idList = List.copyOf(ids);

        // ① 真实赞数：WHERE video_id IN (...) 走 idx_video 范围扫描（查不到 = 0 个赞，用 getOrDefault 兜住）
        Map<Long, Long> realCounts = videoMapper.selectRealLikeCounts(idList).stream()
                .collect(Collectors.toMap(LikeCountPair::getVideoId, LikeCountPair::getCnt, (a, b) -> a));

        // ② 当前派生值：一次主键 IN 查询拿回来
        Map<Long, Long> currentCounts = videoMapper.selectBatchIds(idList).stream()
                .collect(Collectors.toMap(Video::getId, v -> v.getLikeCount() == null ? 0L : v.getLikeCount()));

        // ③ 只在 Java 里比对，只更新有差异的行（稳态下通常 0 行）
        Map<Long, Long> fixed = new HashMap<>();
        for (Long videoId : idList) {
            long real = realCounts.getOrDefault(videoId, 0L);
            long current = currentCounts.getOrDefault(videoId, 0L);
            if (real != current) {
                if (videoMapper.updateLikeCountIfDiff(videoId, real) > 0) {
                    fixed.put(videoId, real);
                }
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
