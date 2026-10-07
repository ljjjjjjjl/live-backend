package com.live.vo;

import lombok.Data;

/**
 * WebSocket 弹幕的可序列化消息结构（发往前端）。
 *
 * 前端根据 type 区分消息类型来渲染：
 *   - CHAT   弹幕文本（data 是完整的 Danmaku 对象）
 *   - ONLINE 直播间在线人数变化（data 是 Integer）
 *   - SYSTEM 系统提示（data 是 String，如"进入直播间"/"主播开播了"）
 */
@Data
public class DanmakuMessage {

    /** 消息类型：CHAT / ONLINE / SYSTEM */
    private String type;

    /** 消息体，具体结构由 type 决定 */
    private Object data;
}
