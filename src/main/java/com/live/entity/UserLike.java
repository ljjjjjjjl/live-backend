package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点赞关系实体，对应 user_like 表。
 *
 * 这张表在推荐系统里的作用：它是"用户行为记录"。
 * 推荐的一个重要来源就是"用户喜欢过什么"。
 *   用户 → user_like → video → 该视频的 category/tags → 算出用户兴趣偏好
 * 所以我们通过 user_like 拿到用户点过赞的视频 id，再去反查这些视频的特征。
 *
 * (user_id, video_id) 有唯一索引，保证一个用户对一个视频最多一条点赞记录。
 */
@Data
@TableName("user_like")
public class UserLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 点赞用户 ID */
    private Long userId;

    /** 被点赞的视频 ID */
    private Long videoId;

    /** 点赞时间 */
    private LocalDateTime createTime;
}
