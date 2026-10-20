package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 直播间实体，对应 live_room 表。
 *
 * 这是直播推荐的对象（视频推荐用的是 Video，直播推荐用的是 LiveRoom）。
 * 直播推荐依赖的特征字段：
 *   - category     分区        → 用来做"相同分区召回"
 *   - online_count 在线人数    → 直播热度核心，排序的主要依据
 *   - like_count   人气/热度   → 热度权重
 *   - is_living    是否直播中  → 直播推荐只推直播中的（与视频推荐的最大差异）
 */
@Data
@TableName("live_room")
public class LiveRoom {

    /** 直播间ID（演示数据用手工id） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 直播标题（创建直播间时必填） */
    @NotBlank(message = "直播标题不能为空")
    @Size(max = 100, message = "直播标题长度不能超过100")
    private String title;

    /** 封面图 */
    private String cover;

    /** 主播ID（关联 user.id） */
    private Long anchorId;

    /** 直播分区，推荐第一特征（创建时必填） */
    @NotBlank(message = "直播分区不能为空")
    @Size(max = 30, message = "直播分区长度不能超过30")
    private String category;

    /** 标签，逗号分隔 */
    @Size(max = 200, message = "标签长度不能超过200")
    private String tags;

    /** 当前在线人数，直播热度核心 */
    private Long onlineCount;

    /** 人气/热度（权重之一） */
    private Long likeCount;

    /** 是否直播中：1=直播中，0=已下播 */
    private Integer isLiving;

    /** 开播时间 */
    private LocalDateTime createTime;

    /**
     * 直播间热度的计算方式。
     * 直播更看重"在线人数"（实时性），所以 online_count 权重最高；
     * like_count(人气)作为辅助。返回 0 表示没热度（不影响排序）。
     *
     * <p>⚠️ 用 {@code Long} 直接相乘会<b>自动拆箱</b>：任一字段为 null 就抛 NullPointerException。
     * 而 {@code LiveRoom} 目前既会从数据库整行读出来，也会被 {@code create} 流程 new 出来，
     * 完全可能出现"只填了部分字段"的实例。这类 NPE 会让整个列表接口 500，
     * 所以这里显式做空值兜底。
     */
    public long liveScore() {
        long online = onlineCount == null ? 0L : onlineCount;
        long like = likeCount == null ? 0L : likeCount;
        return online * 3L + like;
    }
}
