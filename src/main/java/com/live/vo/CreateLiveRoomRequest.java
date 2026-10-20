package com.live.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建直播间请求体（<b>专门给 POST /api/live/rooms 用</b>，不再拿 {@code LiveRoom} 实体接参）。
 *
 * <h3>为什么要单独定义一个 DTO（本次修复的核心）</h3>
 * 原来 Controller 写的是 {@code @Valid @RequestBody LiveRoom room}，于是客户端的 JSON
 * 可以直接决定实体上的**每一个字段**，其中就包括：
 * <pre>
 *   {"id": 别人的直播间ID, "title":"x", "category":"y"}
 * </pre>
 * 而 {@code LiveRoomService#create} 里原来的逻辑是"id 为空才生成"，
 * 且**在 insert 之前**调了 {@code liveLikeService.clearCount(room.getId())}（删 Redis 人气 key）。
 * 由于 Redis 不参与数据库事务，即使紧接着 insert 因主键冲突失败、事务整体回滚，
 * 那个 Redis key 也已经被删掉了 —— 结果就是：
 * <b>任何登录用户都能把任意直播间当场的实时人气清零</b>，反复调用即可无限刷。
 *
 * <h3>修复后的契约</h3>
 * <ul>
 *   <li>入参里**根本没有 id / anchorId / isLiving / onlineCount / likeCount / createTime**，
 *       这些全部由服务端生成或写死（批量赋值漏洞的根治办法就是"字段不在契约里"）；</li>
 *   <li>id 用 MyBatis-Plus 的雪花算法 {@code IdWorker.getId()} 生成 —— 既保证唯一，
 *       也不会像 {@code System.currentTimeMillis()} 那样在**同一毫秒并发创建**时撞主键。</li>
 * </ul>
 */
@Data
public class CreateLiveRoomRequest {

    /** 直播标题（必填；live_room.title 是 varchar(200)，这里按产品要求收紧到 100） */
    @NotBlank(message = "直播标题不能为空")
    @Size(max = 100, message = "直播标题长度不能超过100")
    private String title;

    /** 直播分区（必填；推荐第一特征。live_room.category 是 varchar(50)，所以上限 50） */
    @NotBlank(message = "直播分区不能为空")
    @Size(max = 50, message = "直播分区长度不能超过50")
    private String category;

    /** 标签，逗号分隔（可空） */
    @Size(max = 200, message = "标签长度不能超过200")
    private String tags;

    /** 封面图地址（可空） */
    @Size(max = 500, message = "封面地址长度不能超过500")
    private String cover;
}
