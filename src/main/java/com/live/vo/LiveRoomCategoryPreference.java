package com.live.vo;

import lombok.Data;

/**
 * 用户观看直播间的"分区偏好"查询结果（和视频推荐的 CategoryPreference 对应）。
 * 由 LiveRoomMapper.selectUserCategoryPreference 的 GROUP BY 查询返回：
 *   category : 用户常看的分区名
 *   cnt      : 该分区下用户看过的直播间数量（偏好强度）
 */
@Data
public class LiveRoomCategoryPreference {

    private String category;
    private Long cnt;
}
