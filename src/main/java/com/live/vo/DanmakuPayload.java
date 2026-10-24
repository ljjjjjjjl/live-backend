package com.live.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 弹幕的 <b>WebSocket 广播载荷</b>。
 *
 * <h2>为什么不再叫 entity.Danmaku</h2>
 * 弹幕已经<b>不落库</b>了（见 {@code docs/plan/danmaku-no-persist.md}），
 * 它不再是任何数据库表的映射对象。继续放在 {@code entity} 包里、或者留着
 * {@code @TableName} 注解，会让后来的人以为"还有一张 danmaku 表在写"。
 *
 * <p>所以这里明确降到 {@code vo} 包：它的唯一用途是被序列化成 JSON，
 * 通过 WebSocket 推给房间内的会话。
 *
 * <h2>为什么没有 id</h2>
 * 原来有自增主键 {@code id}，唯一的消费方是前端历史弹幕列表的排序
 * （{@code room.js} 的 {@code records.sort((a,b) => a.id - b.id)}）。
 * 不落库就没有主键，历史列表也一并取消了，所以这个字段没有存在意义。
 *
 * <h2>为什么保留 roomId / userId / createTime</h2>
 * 前端渲染只用到 {@code content} / {@code nickname} / {@code color}，
 * 但前端收到消息后<b>无法反推</b>是哪个房间、哪个用户、什么时候发的。
 * 保留它们是为了排查问题（抓一份 WS 帧就能定位）以及将来做前端侧
 * "本地去重 / 时间线微排"。多余字段不增加渲染成本。
 */
@Data
public class DanmakuPayload {

    /** 直播间 ID（由连接身份决定，不接受前端传入） */
    private Long roomId;

    /** 发送人用户 ID（由握手时解析的 Token 决定） */
    private Long userId;

    /** 发送人昵称（握手时服务端查库得到，不接受前端传入） */
    private String nickname;

    /** 弹幕内容（已过 DFA 敏感词校验） */
    private String content;

    /** 弹幕颜色，#rgb 或 #rrggbb（已归一化） */
    private String color;

    /** 服务端时间戳：给前端留的顺序兜底（多实例广播时不保证严格顺序，见计划文档） */
    private LocalDateTime createTime;
}
