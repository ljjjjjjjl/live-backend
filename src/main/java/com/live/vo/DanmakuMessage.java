package com.live.vo;

import lombok.Data;

/**
 * WebSocket 弹幕的可序列化消息结构（发往前端）。
 *
 * 前端根据 type 区分消息类型来渲染：
 *   - CHAT   弹幕文本（data 是 {@link DanmakuPayload}）
 *   - SYSTEM 系统提示（data 是 String，如"进入直播间"/"发言失败原因"）
 *
 * <p>注意：ONLINE（在线人数变化）这个类型<b>从未实现</b>，前端的对应分支也早已删除
 * （在线人数统一走 5 秒一次的 HTTP 轮询）。这里不再列出，避免误导。
 */
@Data
public class DanmakuMessage {

    /** 消息类型：CHAT / ONLINE / SYSTEM */
    private String type;

    /** 消息体，具体结构由 type 决定 */
    private Object data;
}
