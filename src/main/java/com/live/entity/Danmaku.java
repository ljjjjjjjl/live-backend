package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 弹幕实体，对应 danmaku 表。
 *
 * 和"视频评论"(comment) 不同：弹幕是直播间的即时消息，重点是"实时 + 按房间隔离"。
 * 一张直播间对应一堆弹幕，所以 room_id 是核心外键（逻辑外键，不建物理 FK）。
 */
@Data
@TableName("danmaku")
public class Danmaku {

    /** 弹幕ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 直播间ID（关联 live_room.id） */
    @NotNull(message = "直播间ID不能为空")
    private Long roomId;

    /** 发送用户ID（关联 user.id，可空=游客） */
    private Long userId;

    /** 发送人昵称（冗余存储，避免每条弹幕都连表查 user） */
    private String nickname;

    /** 弹幕内容 */
    @NotBlank(message = "弹幕内容不能为空")
    @Size(max = 200, message = "弹幕内容长度不能超过200")
    private String content;

    /** 弹幕颜色（前端可选，如 #ff0000） */
    private String color;

    /** 发送时间 */
    private LocalDateTime createTime;
}
