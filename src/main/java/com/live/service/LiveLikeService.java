package com.live.service;

import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 直播间点赞（人气）服务。
 *
 * <h2>它和"视频点赞"是同一个词、完全相反的两套方案（面试重点）</h2>
 * <table border="1">
 *   <tr><th></th><th>视频点赞 VideoLikeService</th><th>直播间点赞（本类）</th></tr>
 *   <tr><td>判重</td><td>要（一人一赞，唯一索引兜底）</td><td><b>不要</b>（语义 = 本场人气，可以连点）</td></tr>
 *   <tr><td>取消</td><td>要（DELETE）</td><td><b>不要</b></td></tr>
 *   <tr><td>权威数据</td><td>关系表 {@code user_like}（绝不能丢）</td><td><b>纯计数器</b>，Redis 就是准的</td></tr>
 *   <tr><td>写路径</td><td>事务写库 → 提交后动 Redis → MQ 异步刷回</td><td>只走 Redis 一条 {@code INCRBY}</td></tr>
 *   <tr><td>落库</td><td>实时对账校正</td><td>关播时写回 + 开播归零</td></tr>
 * </table>
 *
 * <p>为什么差这么远？因为选方案的第一个问题不是"用什么技术"，而是：
 * <b>这份数据能不能重算、业务要不要判重</b>。视频点赞牵扯"谁赞过"，必须留权威表；
 * 直播间人气只是"这场有多热"，丢了/算多点都不影响正确性，于是可以直接拿 Redis 当数据源。
 *
 * <h2>为什么这里的计数 key 可以设 TTL（和视频点赞相反）</h2>
 * <p>视频点赞的计数 key <b>不能</b>设 TTL：它是"权威数据的派生值"，一旦过期就会退化成
 * "热 key 被反复续期永不过期、冷 key 又缓存击穿"，而且算错了不会自愈。
 * <p>直播间人气 key 设 24h TTL 是<b>故意的内存兜底</b>：正常直播不可能超过 24 小时，
 * 万一某个房间"开了播但再也没关播"，key 会自己过期回收，不会永久占内存；
 * 真过期了最坏结果也只是"人气从 0 重新数"，用户无感。
 *
 * <h2>限速不是去重</h2>
 * <p>业务允许连点（所以不去重），但接口不能被脚本刷爆，所以做<b>限速</b>：
 * 同一用户在同一房间每秒最多 {@value #RATE_LIMIT_PER_SECOND} 次上报。
 * 限速和计数放在<b>同一个 Lua 脚本</b>里，避免出现"限速过了、计数没加"或反过来的中间态。
 */
@Slf4j
@Service
public class LiveLikeService {

    /** 人气计数 key：live:like:{roomId} */
    private static final String LIKE_COUNT_KEY = "live:like:";

    /** 限速 key：live:like:rate:{roomId}:{userId} */
    private static final String LIKE_RATE_KEY = "live:like:rate:";

    /** 单次上报允许的最大 delta（前端 500ms 攒一批，正常也就几十，给个上限防刷） */
    public static final int MAX_DELTA = 50;

    /** 同一用户在同一房间每秒最多上报次数（限速，不是去重） */
    public static final int RATE_LIMIT_PER_SECOND = 20;

    /** 人气 key 的兜底 TTL：24 小时（见类注释"为什么可以设 TTL"） */
    private static final long COUNT_TTL_SECONDS = 24 * 60 * 60L;

    /**
     * 限速 + 累加人气，一次 Lua 调用完成。
     *
     * <pre>
     *   KEYS[1] = 限速 key     KEYS[2] = 计数 key
     *   ARGV[1] = 每秒上限     ARGV[2] = 限速 key TTL(秒)
     *   ARGV[3] = delta        ARGV[4] = 计数 key TTL(秒)
     *   返回：最新人气；-1 表示触发限速（本次没加）
     * </pre>
     *
     * <p>为什么要 Lua：如果拆成"先 INCR 限速 key 判断、再 INCRBY 计数"两次调用，
     * 两步之间进程挂了就会丢一次点赞；而且高并发下多一次 RTT。原子化后不存在中间态。
     */
    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if n > tonumber(ARGV[1]) then
                return -1
            end
            local c = redis.call('INCRBY', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return c
            """, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    /**
     * 点赞（累加人气）。
     *
     * @return 最新人气；<b>-1 表示被限速</b>（调用方据此提示用户，不要当成异常）
     */
    public long like(Long roomId, Long userId, int delta) {
        Long result = stringRedisTemplate.execute(
                LIKE_SCRIPT,
                List.of(LIKE_RATE_KEY + roomId + ":" + userId, LIKE_COUNT_KEY + roomId),
                String.valueOf(RATE_LIMIT_PER_SECOND),
                "1",
                String.valueOf(delta),
                String.valueOf(COUNT_TTL_SECONDS));
        return result == null ? -1L : result;
    }

    /**
     * 当前人气：<b>Redis 优先</b>，key 不存在才回退到数据库的 {@code live_room.like_count}。
     *
     * <p>为什么要有回退：关播时 key 会被删掉，之后（或服务刚重启、Redis 被清）再查这个房间，
     * 应该显示"上一场结算后的人气"，而不是 0。
     */
    public long currentCount(Long roomId) {
        String cached = stringRedisTemplate.opsForValue().get(LIKE_COUNT_KEY + roomId);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null || room.getLikeCount() == null) {
            return 0L;
        }
        return room.getLikeCount();
    }

    /**
     * 开播：本场人气归零。
     *
     * <p>两件事：删掉 Redis 里上一场残留的计数（本场从 0 重新数）；
     * 数据库的 {@code like_count} 由调用方在同一事务里置 0
     * （见 {@code LiveRoomService#start}，那里本来就有一条 update）。
     *
     * <p>⚠️ 顺序讲究：<b>先删 Redis 再写库</b>。这样万一开播事务回滚了，
     * Redis 里是"没有 key"（读出来回退到数据库的旧值，前后一致），
     * 而不会出现"Redis 说 0、数据库还是旧值"的错位。
     */
    public void clearCount(Long roomId) {
        stringRedisTemplate.delete(LIKE_COUNT_KEY + roomId);
    }

    /**
     * 关播：把本场人气<b>结算写回</b> {@code live_room.like_count}，然后删掉 Redis key。
     *
     * <p>顺序同样是"先写库、再删 key"：<br>
     * ① 先写库——万一写库失败，key 还在，下次关播还能再结算一次；<br>
     * ② 再删 key——删失败也只是下次关播重算一遍同样的值（幂等）。
     *
     * <p>这也正是"关播后排行榜还有数据、开播又是从 0 开始"的实现方式。
     */
    public void flushOnStop(Long roomId) {
        String cached = stringRedisTemplate.opsForValue().get(LIKE_COUNT_KEY + roomId);
        if (cached != null) {
            LiveRoom update = new LiveRoom();
            update.setId(roomId);
            update.setLikeCount(Long.parseLong(cached));
            liveRoomMapper.updateById(update);
            log.info("【直播人气结算】房间 {} 本场人气 {} 已写回数据库，并清除 Redis 计数", roomId, cached);
        }
        stringRedisTemplate.delete(LIKE_COUNT_KEY + roomId);
    }

    /** 仅用于测试/排查：把人气 key 的剩余过期时间读出来（秒） */
    public Long ttlSeconds(Long roomId) {
        return stringRedisTemplate.getExpire(LIKE_COUNT_KEY + roomId, TimeUnit.SECONDS);
    }
}
