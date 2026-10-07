package com.live.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 计数缓存服务（Redis INCR / HINCRBY）：点赞数、收藏数、转发数等互动指标。
 *
 * <p>为什么用 Redis：点赞/收藏/转发是高频 +1 操作，实时 COUNT(*) 在全表扫索引下扛不住高并发。
 * 用 Redis 计数器 O(1) 累加，读也 O(1)——这就是"冗余计数 + 异步落库"（第 2 章讲的 BASE/最终一致）。
 *
 * <p>Redis key 设计（用 Hash 聚合，避免太散）：
 *   <pre>live:count:{targetId} = { like: 12, favorite: 5, forward: 3 }</pre>
 *
 * <p>异步落库：定时/批量把 Redis 计数刷回 MySQL 对应表；对账兜底。这里只做 Redis 侧。
 */
@Service
public class CountCacheService {

    /** 计数器 Hash 的 key 前缀：live:count:{targetId} */
    private static final String COUNT_KEY = "live:count:";

    /** 可累计的计数类型 */
    public static final String LIKE = "like";
    public static final String FAVORITE = "favorite";
    public static final String FORWARD = "forward";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 给某目标（视频/直播间/动态）的某个计数 +1（幂等靠业务侧先判重，如 Set 判断是否已点过）。
     */
    public void incr(Long targetId, String type) {
        stringRedisTemplate.opsForHash().increment(COUNT_KEY + targetId, type, 1);
    }

    /**
     * 读取某目标的某个计数。
     */
    public long getCount(Long targetId, String type) {
        Object val = stringRedisTemplate.opsForHash().get(COUNT_KEY + targetId, type);
        return val == null ? 0L : Long.parseLong(val.toString());
    }

    /** 读取某目标的所有计数（like/favorite/forward） */
    public Map<Object, Object> getAll(Long targetId) {
        return stringRedisTemplate.opsForHash().entries(COUNT_KEY + targetId);
    }
}
