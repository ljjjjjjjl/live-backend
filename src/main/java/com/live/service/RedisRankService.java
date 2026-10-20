package com.live.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的实时互动服务：礼物榜(ZSet) + 在线人数(ZSet + 心跳)。
 *
 * <p>为什么用 Redis：
 * <ul>
 *   <li><b>礼物榜</b>：ZSet 的 {@code ZINCRBY} 累加礼物价值 + {@code ZREVRANGE} 取 TopN，
 *       实时、O(logN)、免数据库排序。</li>
 *   <li><b>在线人数</b>：ZSet 给每个用户记一个"最后一次心跳时间"作为 score，
 *       {@code SCARD} 式的"只加不减"在这里是错的（见下）。</li>
 * </ul>
 *
 * <h2>本轮修复：在线人数"只增不减"（幽灵在线）</h2>
 * <p>改造前用 Set 存"谁在线"，靠前端 {@code beforeunload} 调 `/leave` 来 {@code SREM}。问题是：
 * <ul>
 *   <li><b>关页面不一定发得出请求</b>：断网、崩溃、手机切后台、被杀进程，`beforeunload` 根本不触发；</li>
 *   <li>于是 {@code online:{roomId}} <b>只增不减</b>，在线人数单调上涨，越跑越离谱；</li>
 *   <li>而且 key 没有 TTL，房间结束后也一直留着。</li>
 * </ul>
 * <p>现在改成 <b>ZSet + 心跳</b>：
 * <pre>
 *   online:{roomId}   member = userId   score = 最后一次心跳的时间戳(ms)
 *
 *   进房/心跳   ZADD online:{roomId} &lt;now&gt; {userId}          ← 前端每 20s 打一次
 *   主动离开     ZREM online:{roomId} {userId}
 *   读人数       ZREMRANGEBYSCORE online:{roomId} -inf &lt;now-TTL&gt;   ← 先清掉超时的
 *                ZCARD online:{roomId}
 *                （两件事放在一个 Lua 里做，保证"清"和"数"是一次原子操作）
 * </pre>
 * 这样即使客户端一声不响地消失，最多 {@value #ONLINE_TTL_MS} 毫秒后它也会被自动摘掉。
 *
 * <p>一句话原则：<b>靠客户端"报告离开"永远不可靠；能自愈的方案必须是"靠心跳续期 + 超时剔除"。</b>
 *
 * <p>持久化权衡：Redis 只做实时展示，最终以 MySQL 落库(或定时刷)+ 对账兜底（最终一致）。
 * 在线人数由 {@code OnlineCountSyncTask} 定期回写 {@code live_room.online_count}。
 */
@Service
public class RedisRankService {

    /** 礼物榜 ZSet 的 key 前缀：gift:rank:{roomId} */
    private static final String GIFT_RANK_KEY = "gift:rank:";
    /** 在线人数 ZSet 的 key 前缀：online:{roomId} */
    private static final String ONLINE_KEY = "online:";

    /** 在线心跳超时（毫秒）：超过这个时间没心跳就认为人已经走了。前端每 20s 打一次，留 3 倍余量 */
    public static final long ONLINE_TTL_MS = 60_000L;

    /** 房间在线集合的兜底 TTL：房间长期无人时整个 key 自动回收，避免 Redis 里攒一堆空 key */
    private static final long ONLINE_KEY_TTL_HOURS = 6L;

    /** 礼物榜 TopN 的硬上限：防止有人传 top=1000000 让 ZREVRANGE 拉整张榜单 */
    public static final int GIFT_TOP_MAX = 100;

    /**
     * "先剔除超时成员、再数人数"的原子脚本。
     *
     * <pre>
     *   KEYS[1] = online:{roomId}
     *   ARGV[1] = 超时阈值（当前时间 - TTL，毫秒）
     *   返回：剔完之后的人数
     * </pre>
     *
     * <p>为什么必须原子：如果拆成"先 ZREMRANGEBYSCORE、再 ZCARD"两次往返，
     * 两次之间刚好有人进房，人数就会偏大/偏小；而且多一次 RTT。
     */
    private static final DefaultRedisScript<Long> PURGE_AND_COUNT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local n = redis.call('ZCARD', KEYS[1])
            if n == 0 then
                redis.call('DEL', KEYS[1])
            end
            return n
            """, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ==================== 礼物榜 (ZSet) ====================

    /**
     * 送礼物：给用户在该房间累加礼物价值（主播收到，进礼物榜）。
     *
     * @param roomId 直播间ID
     * @param userId 主播（收礼者）ID
     * @param value  礼物价值（如 100）
     */
    public void addGift(Long roomId, Long userId, double value) {
        stringRedisTemplate.opsForZSet().incrementScore(GIFT_RANK_KEY + roomId, String.valueOf(userId), value);
    }

    /**
     * 取直播间礼物榜 TopN（主播收到礼物价值排行）。
     *
     * <p>本轮修复两点：
     * <ol>
     *   <li>原来的写法是 {@code reverseRange} 拿到 id 之后再对每个 id 单独 {@code score()}，
     *       等于 <b>n+1 次 Redis 往返</b>；现在用 {@code reverseRangeWithScores} 一次拿完；</li>
     *   <li>原来的 {@code n-1} 在 {@code n=0} 时是 {@code -1}，而 Redis 里
     *       {@code ZREVRANGE key 0 -1} 表示<b>整张榜</b>—— 传 {@code top=0} 反而返回全部成员。
     *       现在把 n 夹到 [1, {@value #GIFT_TOP_MAX}] 区间。</li>
     * </ol>
     *
     * @param roomId 直播间ID
     * @param n      前几名（非法值自动收敛到合法区间）
     * @return [{userId, value}] 按分值降序
     */
    public List<Map<String, Object>> giftTopN(Long roomId, int n) {
        int top = Math.max(1, Math.min(n, GIFT_TOP_MAX));
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(GIFT_RANK_KEY + roomId, 0, top - 1);

        List<Map<String, Object>> list = new ArrayList<>();
        if (tuples == null) {
            return list;
        }
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            list.add(Map.of(
                    "userId", t.getValue(),
                    "value", t.getScore() == null ? 0D : t.getScore()
            ));
        }
        return list;
    }

    // ==================== 在线人数 (ZSet + 心跳) ====================

    /**
     * 用户进入直播间 / 上报心跳：把"最后一次活跃时间"写进在线集合。
     *
     * <p>这个方法是<b>幂等</b>的，前端每 20 秒调一次就是"我还活着"的心跳。
     * 同一用户重复调用只是把 score 往后推，不会让人数变多（ZSet 天然按 member 去重）。
     *
     * @param roomId 直播间ID
     * @param userId 用户ID
     */
    public void online(Long roomId, Long userId) {
        String key = ONLINE_KEY + roomId;
        stringRedisTemplate.opsForZSet()
                .add(key, String.valueOf(userId), System.currentTimeMillis());
        // 兜底 TTL：房间彻底没人之后，这个 key 会在 6 小时内自然消失
        stringRedisTemplate.expire(key, ONLINE_KEY_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 用户主动离开直播间：从在线集合移除。
     *
     * <p>注意这只是"加速收敛"：即使这一步没执行到，心跳超时也会把他剔掉。
     */
    public void offline(Long roomId, Long userId) {
        stringRedisTemplate.opsForZSet().remove(ONLINE_KEY + roomId, String.valueOf(userId));
    }

    /**
     * 当前直播间在线人数：<b>先剔除心跳超时的成员，再数</b>（一次 Lua 原子完成）。
     *
     * @param roomId 直播间ID
     * @return 在线人数（key 不存在返回 0）
     */
    public Long onlineCount(Long roomId) {
        Long n = stringRedisTemplate.execute(
                PURGE_AND_COUNT_SCRIPT,
                List.of(ONLINE_KEY + roomId),
                String.valueOf(System.currentTimeMillis() - ONLINE_TTL_MS));
        return n == null ? 0L : n;
    }
}
