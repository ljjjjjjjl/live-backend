package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.entity.UserLike;
import com.live.entity.Video;
import com.live.mapper.UserLikeMapper;
import com.live.mapper.VideoMapper;
import com.live.vo.CategoryPreference;
import com.live.vo.VideoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 推荐系统核心业务逻辑。
 *
 * 这个推荐是个"工程层、能讲清楚"的版本，不是算法模型，分三步走：
 *
 *   ① 冷启动判断：用户有没有行为？没有 → 走"全站热门"兜底
 *   ② 兴趣召回：有行为 → 从点赞记录算出用户最爱的分类 → 推荐该分类的热门视频
 *   ③ 组装结果：把每条结果包成 VideoVO，打上"推荐理由"标签
 *
 * 对应真实推荐流程里的 [召回 → 排序] 两个环节：
 *   - 召回：选出候选集（热门分类 / 全站热门）
 *   - 排序：按热度降序（这里用 play_count 当排序键，简单直观）
 * 真实的排序会换成一个 CTR 模型打分，工程上就是这个雏形。
 */
@Service
public class RecommendService {

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private UserLikeMapper userLikeMapper;

    /** 单次最多返回的推荐条数 */
    private static final int DEFAULT_SIZE = 20;

    /**
     * 推荐入口。
     *
     * @param userId 当前登录用户ID，可为 null（未登录）
     * @param size   想要几条
     * @return 推荐视频列表（已包装成 VO）
     */
    public List<VideoVO> recommend(Long userId, Integer size) {
        int limit = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, 50);

        // 如果用户没传 userId，或者没有行为，走"全站热门"（冷启动兜底）
        if (userId == null) {
            return hotFallback(null, limit);
        }

        // 1) 查用户点过赞的所有视频 id —— 这既是"兴趣来源"，也是"要排除的已看过内容"
        List<UserLike> likes = userLikeMapper.selectList(
                new LambdaQueryWrapper<UserLike>().eq(UserLike::getUserId, userId));
        List<Long> likedVideoIds = likes.stream().map(UserLike::getVideoId).collect(Collectors.toList());

        // 2) 冷启动判断：一个赞都没点过 → 全站热门兜底
        if (likedVideoIds.isEmpty()) {
            return hotFallback(likedVideoIds, limit);
        }

        // 3) 算出用户最爱的分类（兴趣召回依据）
        List<CategoryPreference> prefs = videoMapper.selectUserCategoryPreference(userId);
        if (prefs.isEmpty()) {
            // 极端情况：点赞了但视频都被删了，仍然兜底热门
            return hotFallback(likedVideoIds, limit);
        }

        // 4) 只取前 N 个分类（权重最高的分类），每个分类召回一些热门视频
        //    limit / topCategories 保证"分类召回"和"总条数"匹配，避免某个分类把自己刷屏
        int topCategories = Math.min(prefs.size(), 3); // 最多取用户最爱的 3 个分类
        int perCategoryLimit = Math.max(1, limit / topCategories);
        List<VideoVO> result = new java.util.ArrayList<>();

        for (int i = 0; i < topCategories; i++) {
            String category = prefs.get(i).getCategory();
            List<Video> videos = videoMapper.selectByCategoryExcludeAndOrderByHot(
                    category, likedVideoIds, perCategoryLimit);
            for (Video v : videos) {
                // 推荐理由：跟你常看的分类相关
                result.add(VideoVO.from(v, "与你常看的相似"));
            }
        }

        // 5) 如果分类召回后还不够 limit 条，用全站热门补齐（保证列表长度稳定）
        //    这一步在面试里叫"闭环兜底"，防止冷门分类候选不够。
        if (result.size() < limit) {
            List<Video> hot = videoMapper.selectHotExclude(likedVideoIds, limit - result.size());
            for (Video v : hot) {
                result.add(VideoVO.from(v, "热门"));
            }
        }

        return result;
    }

    /**
     * 全站热门兜底：不区分用户兴趣，直接返回最热的视频。
     * 用于冷启动（没行为 / 没登录）。
     *
     * @param excludeVideoIds 需要排除的视频id（可为 null 或空）
     */
    private List<VideoVO> hotFallback(List<Long> excludeVideoIds, int limit) {
        List<Long> exclude = excludeVideoIds == null ? Collections.emptyList() : excludeVideoIds;
        List<Video> hot = videoMapper.selectHotExclude(exclude, limit);
        // 所有兜底结果统一打"热门"标签
        return hot.stream().map(v -> VideoVO.from(v, "热门")).collect(Collectors.toList());
    }
}
