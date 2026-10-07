package com.live.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Redis 的实时互动服务：礼物榜(ZSet) + 在线人数(Set)。
 *
 * <p>为什么用 Redis：
 * <ul>
 *   <li><b>礼物榜</b>：ZSet 的 {@code ZINCRBY} 累加礼物价值 + {@code ZREVRANGE} 取 TopN，
 *       实时、O(logN)、免数据库排序，且支持分页/时间窗。直播"实时打赏榜"标准做法。</li>
 *   <li><b>在线人数</b>：Set 的 {@code SADD}/{@code SREM} 维护"谁在线"，{@code SCARD} 取人数，
 *       比 COUNT(*) 实时且扛高并发。</li>
 * </ul>
 *
 * <p>持久化权衡：Redis 只做实时展示，最终以 MySQL 落库(或定时刷)+ 对账兜底（最终一致）。
 */
@Service
public class RedisRankService {

    /** 礼物榜 ZSet 的 key 前缀：gift:rank:{roomId} */
    private static final String GIFT_RANK_KEY = "gift:rank:";
    /** 在线人数 Set 的 key 前缀：online:{roomId} */
    private static final String ONLINE_KEY = "online:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;   // 存纯字符串（id/分数），用 StringRedisTemplate 最稳

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ==================== 礼物榜 (ZSet) ====================

    /**
     * 送礼物：给用户在该房间累加礼物价值（主播收到，进礼物榜）。
     * 谁送不重要，这里以"收礼用户(userId)=被送的人"入榜；也可改成送礼人维度。
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
     * @param roomId 直播间ID
     * @param n      前几名
     * @return [{userId, score}] 按分值降序
     */
    public List<Map<String, Object>> giftTopN(Long roomId, int n) {
        Set<String> ids = stringRedisTemplate.opsForZSet()
                .reverseRange(GIFT_RANK_KEY + roomId, 0, n - 1);   // 从高分到低分取前 n
        List<Map<String, Object>> list = new ArrayList<>();
        if (ids == null) {
            return list;
        }
        for (String id : ids) {
            Double score = stringRedisTemplate.opsForZSet()
                    .score(GIFT_RANK_KEY + roomId, id);
            list.add(Map.of(
                    "userId", id,
                    "value", score == null ? 0D : score
            ));
        }
        return list;
    }

    // ==================== 在线人数 (Set) ====================

    /**
     * 用户进入直播间：加入在线集合。
     *
     * @param roomId 直播间ID
     * @param userId 用户ID
     */
    public void online(Long roomId, Long userId) {
        stringRedisTemplate.opsForSet().add(ONLINE_KEY + roomId, String.valueOf(userId));
    }

    /**
     * 用户离开直播间：从在线集合移除。
     *
     * @param roomId 直播间ID
     * @param userId 用户ID
     */
    public void offline(Long roomId, Long userId) {
        stringRedisTemplate.opsForSet().remove(ONLINE_KEY + roomId, String.valueOf(userId));
    }

    /**
     * 当前直播间在线人数。
     *
     * @param roomId 直播间ID
     * @return 在线人数
     */
    public Long onlineCount(Long roomId) {
        Long size = stringRedisTemplate.opsForSet().size(ONLINE_KEY + roomId);
        return size == null ? 0L : size;
    }
}
