package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频实体，对应 video 表。
 *
 * 这是推荐系统的主角：
 * 推荐的核心就是把这几个"特征字段"喂给推荐逻辑，
 *   - category    分区/分类      → 用来做"相同分类召回"
 *   - tags        标签          → 用来计算用户兴趣（用户点过哪些标签）
 *   - play_count  播放量        → 热度权重，热度排序的素材
 *   - 各种计数    点赞/收藏/评论/转发 → 热度加权，决定视频"火不火"
 */
@Data
@TableName("video")
public class Video {

    /** 视频ID。演示数据用手工指定的 id，所以这里不是 AUTO 自增 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 视频标题 */
    private String title;

    /** 封面图地址 */
    private String cover;

    /** 作者/UP主 ID（关联 user.id），推荐后要展示作者信息 */
    private Long authorId;

    /** 分区/分类，推荐的第一特征：同分类的视频更容易被推荐给喜欢该分类的用户 */
    private String category;

    /** 标签，逗号分隔，如 "英雄联盟,LOL,电竞"。用来算"用户喜欢哪些标签" */
    private String tags;

    /** 播放量，热度权重之一 */
    private Long playCount;

    /** 点赞数（热度权重） */
    private Long likeCount;

    /** 收藏数（热度权重） */
    private Long favoriteCount;

    /** 评论数（热度权重） */
    private Long commentCount;

    /** 转发数（热度权重） */
    private Long forwardCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /**
     * 计算视频的综合热度分（这就是"排行榜/热门"怎么排的）。
     * 不同行为的价值不同，所以给不同权重：
     *   播放量权重低（最容易产生，说明不了多喜欢）
     *   点赞/收藏/转发权重高（代表真实的正向兴趣）
     * 冷门视频很可能播放量少，但热度分能真实反映质量。
     *
     * 这是一个纯计算的辅助方法，不算字段，也不会落库。
     */
    public long heatScore() {
        // 权重可自定义：重点放大"收藏/转发/点赞"，播放量权重放低
        return playCount * 1L
                + likeCount * 5L
                + favoriteCount * 10L
                + commentCount * 8L
                + forwardCount * 6L;
    }
}
