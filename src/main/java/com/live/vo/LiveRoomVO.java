package com.live.vo;

import com.live.entity.LiveRoom;
import lombok.Data;

/**
 * 直播间推荐结果视图对象（和 VideoVO 对应，只是对象换成直播间）。
 * 额外带 reason（推荐理由），前端可渲染角标。
 */
@Data
public class LiveRoomVO {

    private Long id;
    private String title;
    private String cover;
    private String category;

    /**
     * 主播ID。
     *
     * <p>为什么必须暴露？送礼的目标是"这个房间的主播"，前端不该让用户手填一个 userId
     * （填错了就送到别人头上）。带上 anchorId，前端才能自动定位收礼者；
     * 服务端也会用它校验前端传来的 receiverId（见 {@code LiveInteractiveController#gift}）。
     */
    private Long anchorId;

    /** 在线人数（直播更看重这个） */
    private Long onlineCount;
    private Long liveScore;

    /** 是否直播中 */
    private Integer isLiving;

    /** 推荐理由："热门" / "与你常看的相似" */
    private String reason;

    /** 从 LiveRoom 转成 VO，带推荐理由 */
    public static LiveRoomVO from(LiveRoom r, String reason) {
        LiveRoomVO vo = new LiveRoomVO();
        vo.id = r.getId();
        vo.title = r.getTitle();
        vo.cover = r.getCover();
        vo.category = r.getCategory();
        vo.anchorId = r.getAnchorId();
        vo.onlineCount = r.getOnlineCount();
        vo.liveScore = r.liveScore();
        vo.isLiving = r.getIsLiving();
        vo.reason = reason;
        return vo;
    }
}
