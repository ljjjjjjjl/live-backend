package com.live.service;

import com.live.mapper.LiveRoomMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播间「是否存在」的本地缓存 —— 存在的唯一目的是把查库从点赞热路径上挪走。
 *
 * <h2>为什么需要它（压测实测数据，单机 / MySQL+Redis+应用同机）</h2>
 * <p>改造前 {@code POST /api/live/{roomId}/like} 每次请求都做一次
 * {@code liveRoomMapper.selectById(roomId)}。把这条链路逐段拆开量下来：
 *
 * <table border="1">
 *   <tr><th>测量对象</th><th>P50</th><th>说明</th></tr>
 *   <tr><td>MySQL 执行同一条主键查询</td><td>0.52 ms</td><td>整行只有 10 列 85 字符，行不宽</td></tr>
 *   <tr><td>不碰数据库的端点（读 Redis）</td><td>1.58 ms</td><td>纯 Redis GET</td></tr>
 *   <tr><td>碰数据库的端点（本接口）</td><td>5.97 ms</td><td>多出来的 4.4ms 全在应用侧</td></tr>
 *   <tr><td>MySQL 连接是否被频繁重建</td><td>否</td><td>打 100 次请求，Connections 增量 = 0</td></tr>
 *   <tr><td>SQL 日志是否在打</td><td>否</td><td>jcmd 确认启动参数已把 mapper 日志压到 warn</td></tr>
 * </table>
 *
 * <p>也就是说：<b>数据库本身只要 0.5ms，而"取连接 + MyBatis 参数绑定/结果映射"这一段要 4ms 以上。</b>
 * 这段开销跟 SQL 写得快不快无关，只跟"这次请求要不要碰数据库"有关 ——
 * 所以唯一的优化方向就是：<b>别碰</b>。
 *
 * <h2>为什么可以缓存</h2>
 * <p>房间的创建/开播是低频操作，而且房间号一旦存在就几乎不会再消失。
 * 「这个房间号是不是真的」是一个典型的读多写少、可以短暂不一致的问题：
 * 就算某房间刚被删掉、这里还认为它存在 60 秒，最坏结果也只是给一个空房间的 Redis 计数
 * 多加几分，用户看不到任何异常。反过来，把 4ms 的查库留在每秒上千次的点赞路径上，
 * 代价是整条链路吞吐被钉死。
 *
 * <h2>为什么"不存在"也要缓存（而且是更短的 TTL）</h2>
 * <p>不存在的房间号是<b>攻击者可以无限编造</b>的输入。如果只缓存命中的结果，
 * 那么刷 {@code /api/live/999999999/like} 的请求会 100% 穿透到数据库，
 * 等于把缓存变成了一台「放大攻击」的机器。所以未命中也要缓存，只是 TTL 缩短到 10 秒 ——
 * 这样即使真的新建了房间，最多 10 秒后就能正常点赞。
 */
@Slf4j
@Component
public class RoomExistsCache {

    /** 命中缓存的有效期：60 秒。房间存在与否不会在 1 分钟内频繁变化 */
    private static final long TTL_HIT_MS = 60_000L;

    /** 未命中的有效期：10 秒。短一些，避免房间刚建好却点不了赞 */
    private static final long TTL_MISS_MS = 10_000L;

    /**
     * 缓存条目上限。
     * 正常项目里房间数是几万级，远到不了这个数；设这个上限纯粹是为了防「有人拿随机房间号把本地缓存撑爆」——
     * 一旦到顶就整体清空，宁愿重新查一轮库，也不让内存无界增长。
     */
    private static final int MAX_ENTRIES = 10_000;

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    /** key = 房间 ID；值里带一个过期时间戳，读的时候判一下即可（不需要额外的清理线程） */
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    /** 一次「存在性判定」的结果 + 它的过期时刻 */
    private static final class Entry {
        final boolean exists;
        final long expireAt;

        Entry(boolean exists, long expireAt) {
            this.exists = exists;
            this.expireAt = expireAt;
        }
    }

    /**
     * 该直播间是否存在。
     *
     * <p>命中缓存时是一次 {@link ConcurrentHashMap#get}，不碰数据库；
     * 未命中时才真的 {@code selectById}，随后把结果（含"不存在"）写回缓存。
     *
     * @param roomId 直播间 ID；为 {@code null} 时直接当不存在处理
     */
    public boolean exists(Long roomId) {
        if (roomId == null) {
            return false;
        }
        long now = System.currentTimeMillis();

        Entry cached = cache.get(roomId);
        if (cached != null && cached.expireAt > now) {
            return cached.exists;
        }

        boolean exists = liveRoomMapper.selectById(roomId) != null;

        // 只在真的查了库之后才考虑容量，避免把自己变成热点
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
            log.warn("【房间存在性缓存】条目数超过 {}，已整体清空（疑似有客户端用随机房间号刷接口）", MAX_ENTRIES);
        }
        cache.put(roomId, new Entry(exists, now + (exists ? TTL_HIT_MS : TTL_MISS_MS)));
        return exists;
    }
}
