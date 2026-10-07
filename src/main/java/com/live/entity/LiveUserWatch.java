package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户观看直播间记录实体，对应 live_user_watch 表。
 * 直播推荐的"行为数据"：用户看过哪些直播间。
 * 通过它 → 反查这些直播间的分区 → 算出用户常看的分区偏好。
 * 和视频推荐里的 user_like 作用完全一样，只是换了行为对象。
 */
@Data
@TableName("live_user_watch")
public class LiveUserWatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 观看用户ID */
    private Long userId;

    /** 观看的直播间ID */
    private Long roomId;

    /** 观看时间 */
    private LocalDateTime createTime;
}
