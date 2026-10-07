package com.live.vo;

import com.live.entity.Video;
import lombok.Data;

/**
 * 视频推荐结果视图对象（View Object）。
 *
 * 为什么推荐接口不直接返回 Video？
 * 因为推荐除了视频本身，还要额外告诉前端"为什么推荐给你"（推荐理由），
 * 这样前端能在卡片上打个小标签（比如"你常看知识"），体验更好，面试也是加分点。
 * 所以用一个独立的 VO 包装一下，Video 表怎么变，接口只分这一层，不动对外契约。
 */
@Data
public class VideoVO {

    /** 视频基本信息 */
    private Long id;
    private String title;
    private String cover;
    private String category;

    /** 播放量 / 热度分，展示用 */
    private Long playCount;
    private Long heatScore;

    /**
     * 点赞数。
     *
     * <p>注意它的<b>数据来源与语义</b>：video 表的 like_count 是一个<b>派生值</b>
     * （由权威表 user_like 聚合而来），通过"异步刷回 + 定时对账"保证最终一致，
     * 所以列表页上的数字可能有秒级延迟，实时值以点赞接口返回的为准。
     */
    private Long likeCount;

    /**
     * 推荐理由标签，例如：
     *   "热门"        —— 冷启动/兜底时给
     *   "与你常看的相似" —— 基于用户行为召回时给
     * 前端可据此渲染推荐来源角标。
     */
    private String reason;

    /**
     * 从 Video 转成 VideoVO 的便捷方法。
     * 传入 reason 把"推荐理由"一起带出去。
     */
    public static VideoVO from(Video v, String reason) {
        VideoVO vo = new VideoVO();
        vo.id = v.getId();
        vo.title = v.getTitle();
        vo.cover = v.getCover();
        vo.category = v.getCategory();
        vo.playCount = v.getPlayCount();
        vo.likeCount = v.getLikeCount(); // 点赞数（派生值，见字段说明）
        vo.heatScore = v.heatScore(); // 顺手把热度分也带出来
        vo.reason = reason;
        return vo;
    }
}
