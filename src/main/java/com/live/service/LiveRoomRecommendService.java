package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.entity.LiveUserWatch;
import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import com.live.mapper.LiveUserWatchMapper;
import com.live.vo.LiveRoomCategoryPreference;
import com.live.vo.LiveRoomVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 直播间推荐核心逻辑。
 *
 * 和 RecommendService（视频推荐）完全同构，只是对象从 Video 换成 LiveRoom：
 *   ① 冷启动：没行为 → 全站最热的直播间（在线人数最多且直播中）
 *   ② 兴趣召回：有行为 → 从观看记录算出用户常看分区 → 推荐该分区直播中的直播间
 *   ③ 排序 + 去重 + 兜底
 *
 * 与视频推荐的最大差异：直播推荐**只推 is_living=1（正在直播）的直播间**，
 * 因为下播的没法看，推到列表没意义。
 */
@Service
public class LiveRoomRecommendService {

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    @Autowired
    private LiveUserWatchMapper liveUserWatchMapper;

    private static final int DEFAULT_SIZE = 20;

    /**
     * 直播间推荐入口。
     * @param userId 当前用户，可为 null
     * @param size   要几条
     */
    public List<LiveRoomVO> recommend(Long userId, Integer size) {
        int limit = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, 50);

        // 没登录 → 全站热门直播间兜底
        if (userId == null) {
            return hotFallback(null, limit);
        }

        // 1) 查用户看过哪些直播间（既是兴趣来源，也是要排除的"已看过"）
        List<LiveUserWatch> watches = liveUserWatchMapper.selectList(
                new LambdaQueryWrapper<LiveUserWatch>().eq(LiveUserWatch::getUserId, userId));
        List<Long> watchedRoomIds = watches.stream().map(LiveUserWatch::getRoomId).collect(Collectors.toList());

        // 2) 冷启动：一条没看过 → 全站热门兜底
        if (watchedRoomIds.isEmpty()) {
            return hotFallback(watchedRoomIds, limit);
        }

        // 3) 算用户常看的分区
        List<LiveRoomCategoryPreference> prefs = liveRoomMapper.selectUserCategoryPreference(userId);
        if (prefs.isEmpty()) {
            return hotFallback(watchedRoomIds, limit);
        }

        // 4) 按常看分区召回直播中的直播间
        int topCategories = Math.min(prefs.size(), 3);
        int perCategoryLimit = Math.max(1, limit / topCategories);
        List<LiveRoomVO> result = new java.util.ArrayList<>();

        for (int i = 0; i < topCategories; i++) {
            String category = prefs.get(i).getCategory();
            List<LiveRoom> rooms = liveRoomMapper.selectByCategoryExcludeAndOrderByHot(
                    category, watchedRoomIds, perCategoryLimit);
            for (LiveRoom r : rooms) {
                result.add(LiveRoomVO.from(r, "与你常看的相似"));
            }
        }

        // 5) 不够则用全站热门直播间补齐
        if (result.size() < limit) {
            List<LiveRoom> hot = liveRoomMapper.selectHotExclude(watchedRoomIds, limit - result.size());
            for (LiveRoom r : hot) {
                result.add(LiveRoomVO.from(r, "热门"));
            }
        }

        return result;
    }

    /** 全站热门直播间兜底（只推直播中的） */
    private List<LiveRoomVO> hotFallback(List<Long> excludeRoomIds, int limit) {
        List<Long> exclude = excludeRoomIds == null ? Collections.emptyList() : excludeRoomIds;
        List<LiveRoom> hot = liveRoomMapper.selectHotExclude(exclude, limit);
        return hot.stream().map(r -> LiveRoomVO.from(r, "热门")).collect(Collectors.toList());
    }
}
