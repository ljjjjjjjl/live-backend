package com.live.vo;

import lombok.Data;

/**
 * 直播间点赞上报体。
 *
 * <p>为什么是 {@code delta}（本次上报几个赞）而不是"一个赞一次请求"：
 * 直播间点赞允许连点，用户疯狂点的时候前端<b>本地先攒着</b>，每 500ms 合并成一次请求上报。
 * 这样 100 次点击只打 1 次接口，把"高频小请求"压成"低频批量请求"——
 * 服务端再用 Lua 一次 {@code INCRBY delta} 原子累加。
 *
 * <p>不传 delta 时默认 1（单个赞的普通场景也能直接调）。
 */
@Data
public class LiveLikeRequest {

    /** 本次上报的点赞数，1 ~ {@code LiveLikeService.MAX_DELTA} */
    private Integer delta;
}
