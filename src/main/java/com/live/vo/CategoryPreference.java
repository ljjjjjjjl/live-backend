package com.live.vo;

import lombok.Data;

/**
 * 用户分类偏好（推荐系统内部用的查询结果）。
 *
 * 由 VideoMapper.selectUserCategoryPreference 这个 GROUP BY 查询返回：
 *   category : 某个分类名
 *   cnt      : 用户在这个分类下点过赞的视频数量（即"偏好强度"）
 *
 * cnt 越大，说明用户越爱看这个分类，推荐时就优先推它。
 */
@Data
public class CategoryPreference {

    /** 分类名，如 知识 / 游戏 */
    private String category;

    /** 该分类下用户点赞的视频个数（偏好权重） */
    private Long cnt;
}
