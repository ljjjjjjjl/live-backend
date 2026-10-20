package com.live.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import com.live.service.RedisRankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在线人数<b>回写</b>任务：把 Redis 里的实时在线数写回 {@code live_room.online_count}。
 *
 * <h2>为什么需要它</h2>
 * Redis 里的 {@code online:{roomId}} 是实时数据源，但 {@code live_room.online_count} 这个列
 * 之前<b>从来没有人写过</b>（永远是 0），而它恰好是：
 * <ul>
 *   <li>直播间列表的主排序键（{@code LiveRoomService#list} 的 {@code ORDER BY online_count DESC}）；</li>
 *   <li>直播间推荐热度分的核心（{@code ORDER BY online_count * 3 + like_count}）；</li>
 *   <li>索引 {@code idx_living_online} 的列。</li>
 * </ul>
 * 也就是说：排序键是常量 0，等于"按在线人数排序"这件事<b>根本没生效</b>，只是没人发现而已。
 *
 * <h2>为什么是"定时回写"而不是"有人进房就 UPDATE"</h2>
 * 进房是高频写（每个用户每次进房都要写），而"列表排序"对秒级延迟完全不敏感。
 * 用一个低频任务把 Redis 的实时值刷回数据库，写放大从"每进房一次"降到"每 30 秒每房间一次"。
 * 这是典型的<b>读写分离思路</b>：高频路径只碰 Redis，DB 只做低频快照。
 *
 * <p>只处理 {@code is_living=1} 的房间，且限制单轮房间数，避免任务自己变成大事务。
 */
@Slf4j
@Component
public class OnlineCountSyncTask {

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    @Autowired
    private RedisRankService redisRankService;

    /** 单轮最多同步多少个直播间（演示环境房间很少，这里给个上限防止无界扫描） */
    @Value("${app.live.online-sync-batch:500}")
    private int batchSize;

    /**
     * 每 30 秒把"直播中"房间的在线人数从 Redis 刷回数据库。
     *
     * <p>用 {@code fixedDelay}：上一轮跑完再等 30 秒，不会出现两轮重叠。
     * 异常自己兜住，不能让定时任务"悄悄死掉"。
     */
    @Scheduled(fixedDelayString = "${app.live.online-sync-interval:30000}")
    public void syncOnlineCount() {
        try {
            List<LiveRoom> living = liveRoomMapper.selectList(new LambdaQueryWrapper<LiveRoom>()
                    .select(LiveRoom::getId, LiveRoom::getOnlineCount)
                    .eq(LiveRoom::getIsLiving, 1)
                    .last("limit " + Math.max(1, batchSize)));

            int changed = 0;
            for (LiveRoom room : living) {
                long real = redisRankService.onlineCount(room.getId());
                long current = room.getOnlineCount() == null ? 0L : room.getOnlineCount();
                if (real == current) {
                    continue;   // 没变化就不写：稳态下几乎不产生 binlog 和行锁
                }
                liveRoomMapper.update(null, new LambdaUpdateWrapper<LiveRoom>()
                        .eq(LiveRoom::getId, room.getId())
                        .set(LiveRoom::getOnlineCount, real));
                changed++;
            }
            if (changed > 0) {
                log.debug("【在线人数回写】本轮同步 {} 个直播间，更新 {} 行", living.size(), changed);
            }
        } catch (Exception e) {
            // 定时任务里的异常必须自己兜住：抛出去只会被调度器吞掉/打断后续调度
            log.error("【在线人数回写】执行失败", e);
        }
    }
}
