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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
     * "排除列表"最多带多少个 id（理由同 {@code RecommendService} 的 {@code MAX_EXCLUDE}：
     * 排除是靠 SQL 的 {@code id NOT IN (...)} 实现的，列表长了会拖垮解析和传输）。
     */
    private static final int MAX_EXCLUDE = 200;

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

        // 1) 查用户最近看过的直播间（既是兴趣来源，也是要排除的"已看过"）
        List<Long> watchedRoomIds = liveUserWatchMapper.selectList(
                        new LambdaQueryWrapper<LiveUserWatch>()
                                .select(LiveUserWatch::getRoomId)
                                .eq(LiveUserWatch::getUserId, userId)
                                .orderByDesc(LiveUserWatch::getCreateTime)
                                .last("limit " + MAX_EXCLUDE))
                .stream().map(LiveUserWatch::getRoomId).collect(Collectors.toList());

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

        // ⚠️ exclude 同时包含"看过的"和"这轮已召回的"：否则第 5 步兜底补齐
        //    会把第 4 步已经返回的直播间再查一遍，推荐流里出现重复卡片
        Set<Long> exclude = new LinkedHashSet<>(watchedRoomIds);

        for (int i = 0; i < topCategories; i++) {
            String category = prefs.get(i).getCategory();
            List<LiveRoom> rooms = liveRoomMapper.selectByCategoryExcludeAndOrderByHot(
                    category, new ArrayList<>(exclude), perCategoryLimit);
            for (LiveRoom r : rooms) {
                result.add(LiveRoomVO.from(r, "与你常看的相似"));
                exclude.add(r.getId());
            }
        }

        // 5) 不够则用全站热门直播间补齐
        if (result.size() < limit) {
            List<LiveRoom> hot = liveRoomMapper.selectHotExclude(new ArrayList<>(exclude), limit - result.size());
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
