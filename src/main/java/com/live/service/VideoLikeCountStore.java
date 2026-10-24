package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.entity.UserLike;
import com.live.mapper.UserLikeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 视频点赞「派生计数」的唯一读写入口 —— {@code video:like:count:{videoId}} 这个 Redis key 的所有操作都在这。
 *
 * <h2>为什么要把这段从 VideoLikeService 抽出来</h2>
 * 因为「把增量应用到 Redis 计数」这个动作现在有两个调用方：
 * <ul>
 *   <li>{@link SyncLikeCountPublisher} —— MQ 关闭时，在请求线程里同步执行；</li>
 *   <li>{@code VideoLikeCountConsumer} —— MQ 打开时，在消费线程里异步执行。</li>
 * </ul>
 * 如果这段逻辑还留在 {@code VideoLikeService} 里，那么同步实现就要反过来依赖它，
 * 而它又依赖 {@code LikeCountPublisher} —— 直接构成<b>循环依赖</b>。
 * 抽成独立的 Store 后，两边都只依赖这里，依赖方向变成单向。
 *
 * <h2>为什么计数类缓存不能设 TTL（原注释，保留）</h2>
 * <ul>
 *   <li><b>热 key 被不断续期 → 永不过期</b>：一旦计数算错，就长期错下去；</li>
 *   <li><b>冷 key 过期 → 缓存击穿</b>：没人看的视频 key 一过期，恰好来一波请求，
 *       就同时去 {@code SELECT COUNT(*)} 重建，把 MySQL 打死；</li>
 *   <li>逻辑正好搞反了：<b>该自愈的（热 key）不自愈，不该有流量的（冷 key）反而集中打库</b>。</li>
 * </ul>
 * 而计数数据本身量小、可重算（丢了从权威表 {@code user_like} COUNT(*) 重建），
 * TTL 对它只有风险没有收益。
 *
 * <p>⚠️ <b>配套要求</b>：不设 TTL 意味着内存只增不减，必须靠 Redis 服务端的内存淘汰策略兜底，
 * 配 {@code maxmemory-policy allkeys-lru}（见 {@code application.yml} 的 Redis 段注释）。
 * 不能用 {@code volatile-lru} —— 它只淘汰"设了 TTL 的 key"，而这些 key 恰恰都没有 TTL。
 */
@Slf4j
@Component
public class VideoLikeCountStore {

    /**
     * 「存在则原子累加、不存在则让调用方去重建」的 Lua 脚本。
     *
     * <p><b>为什么必须用 Lua？</b> 原来的写法是 Java 里分两步：
     * <pre>
     *   if (hasKey(key)) increment(key, delta); else rebuild(key);
     * </pre>
     * {@code hasKey} 和 {@code increment} 之间是两次独立往返，中间 key 一旦消失，
     * 就会走错分支（或对空 key 从 0 起算）。Lua 在 Redis 单线程里执行，
     * 「判断 + 累加」是一次原子操作，窗口被彻底消除。
     *
     * <p>返回 -1 = key 不存在，交给 {@link #applyDelta} 用数据库真值回填。
     * （计数不可能为负：取消点赞有"行数门控"，见 {@code VideoLikeService#unlike}，所以 -1 可安全当哨兵。）
     */
    private static final DefaultRedisScript<Long> INCR_IF_EXISTS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserLikeMapper userLikeMapper;

    /**
     * 缓存未命中时，用「<b>当前</b>数据库真实计数」把 Redis 计数捂热。
     *
     * <p>⚠️ <b>必须在修改数据库之前调用！</b> 这是一个非常容易踩的坑：
     * 重建计数靠的是 {@code SELECT COUNT(*)}，如果等 INSERT/DELETE 做完再重建，
     * 那次 COUNT 反映的已经是<b>改完之后</b>的数字，再叠加 ±1 就会：
     * <ul>
     *   <li>点赞时 → 重复计数（多算 1）</li>
     *   <li>取消时 → 重复递减（少算 1）</li>
     * </ul>
     * 这个偏差一旦留下就很难发现 —— 属于"测试不仔细就看不出来"的隐蔽 bug。
     *
     * <p>那为什么还要"捂热"而不是让 {@code INCRBY} 自己从 0 开始？因为 {@code INCRBY} 对
     * <b>不存在的 key 会从 0 起算</b>：某视频真实有 5 个赞，缓存缺失时一个人点赞，
     * 直接 INCR 会得到 1 而不是 6。
     */
    public void warmCountIfAbsent(Long videoId) {
        String key = VideoLikeService.countKey(videoId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        rebuildCount(videoId);
    }

    /**
     * 查询点赞总数（Cache Aside）。
     *
     * <p>缓存未命中时，从<b>权威表 user_like</b> 重算并回填，而不是读 {@code video.like_count}
     * —— 因为后者是异步刷回的派生值，可能是旧的。
     */
    public long getLikeCount(Long videoId) {
        String key = VideoLikeService.countKey(videoId);

        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException e) {
                // 缓存里被写入了非法值（人为误操作等）→ 删掉当未命中处理，别让脏值一直返回
                log.warn("点赞计数缓存值非法，key={}, value={}，已清除", key, cached);
                stringRedisTemplate.delete(key);
            }
        }
        return rebuildCount(videoId);
    }

    /**
     * 从权威表 user_like 重算并回填 Redis。
     *
     * <p>⚠️ <b>不带 TTL</b>：见类注释「为什么计数类缓存不能设 TTL」。
     */
    public long rebuildCount(Long videoId) {
        Long cnt = userLikeMapper.selectCount(new LambdaQueryWrapper<UserLike>()
                .eq(UserLike::getVideoId, videoId));
        long value = cnt == null ? 0L : cnt;
        stringRedisTemplate.opsForValue().set(VideoLikeService.countKey(videoId), String.valueOf(value));
        return value;
    }

    /**
     * 给计数加减 delta（原子）。
     *
     * <p><b>实现要点</b>：
     * <ol>
     *   <li><b>原子</b>：用 Lua 把"判断 key 是否存在 + INCRBY"合并成一次操作，
     *       不再有 {@code hasKey} 与 {@code increment} 之间的竞态窗口；</li>
     *   <li><b>不续 TTL</b>：删掉了原来的 {@code expire(key, COUNT_TTL)} —— 那正是
     *       "热 key 永不过期、算错不自愈"的根因；</li>
     *   <li><b>缺失时用数据库真值兜底</b>：本方法是在<b>数据库已经改完之后</b>被调用的，
     *       所以此刻数据库的计数<b>就是</b>正确的当前值 → 直接回填，
     *       <b>绝不能再叠加 delta</b>（否则就是上面那个重复计数 bug）。</li>
     * </ol>
     *
     * <p><b>异步化之后的注意点</b>：走 MQ 时本方法在<b>消费线程</b>里执行，
     * 而此时数据库早已提交，所以"缺失时用数据库真值兜底"这条前提依然成立 ——
     * 这也是这条链路可以安全异步化的关键。
     */
    public void applyDelta(Long videoId, long delta) {
        String key = VideoLikeService.countKey(videoId);

        Long result = stringRedisTemplate.execute(
                INCR_IF_EXISTS_SCRIPT, List.of(key), String.valueOf(delta));

        if (result == null || result < 0) {
            // 走到这里说明 key 在"捂热"之后消失了（Redis 重启/flush 等运维级异常）。
            // 注意：这是"慢路径"兜底，重建的是改库之后的真值，不叠加 delta。
            log.warn("点赞计数 key 缺失，按键数据库真值重建：videoId={}, delta={}", videoId, delta);
            rebuildCount(videoId);
        }
    }
}
