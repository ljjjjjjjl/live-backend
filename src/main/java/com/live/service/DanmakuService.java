package com.live.service;

import com.live.common.BusinessException;
import com.live.vo.DanmakuPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 弹幕业务逻辑：<b>内容审核 + 构造广播载荷</b>。
 *
 * <h2>职责变更（2026 年本轮改造）</h2>
 * 原来这个类有两件事：
 * <ol>
 *   <li>{@code save(Danmaku)}：DFA 敏感词校验 + <b>INSERT 落库</b>（带 {@code @Transactional}）；</li>
 *   <li>{@code history(roomId, pageNum, pageSize)}：历史弹幕分页查询。</li>
 * </ol>
 * 现在弹幕<b>不再落库</b>（见 {@code docs/plan/danmaku-no-persist.md}）：
 * 落库、历史查询、{@code DanmakuMapper}、{@code entity.Danmaku} 全部移除，
 * 本类只保留"审核"这一件事，并顺带负责组装 {@link DanmakuPayload}。
 *
 * <h2>为什么这个类不一起删掉</h2>
 * 敏感词校验曾经藏在 {@code save()} 里，而 {@code save()} 的唯一调用方是
 * WebSocket 处理器。如果连同 {@code save()} 直接删掉，<b>内容审核会静默消失</b> ——
 * 而弹幕是直接推给房间内所有观众的，这等于把审核这道闸门整个拆了。
 *
 * <p>所以把它单独提出来保留在这里，有两个好处：
 * <ul>
 *   <li>WebSocket 处理器只负责"连接管理 + 广播"，审核策略集中在一处，将来换成
 *       正则 / 词库 / 第三方内容安全接口，只改这里；</li>
 *   <li>将来如果要用 MQ 做弹幕分发，这个类正好是"校验通过 → 投递"的天然切点。</li>
 * </ul>
 *
 * <h2>为什么没有 {@code @Transactional}</h2>
 * 本类<b>不碰数据库</b>：DFA 校验是纯内存 Trie 匹配，载荷是普通对象。
 * 没有事务边界可谈 —— 原来的 {@code @Transactional} 是给那条 INSERT 用的，
 * 那条 INSERT 已经不存在了。
 */
@Service
public class DanmakuService {

    @Autowired
    private SensitiveWordService sensitiveWordService;

    /**
     * 校验弹幕内容并构造广播载荷。
     *
     * <p>校验不通过时抛 {@link BusinessException}，由调用方（WebSocket 处理器）
     * 捕获后回一条系统提示给发送者，并且<b>不广播</b> —— WebSocket 通道没有
     * {@code GlobalExceptionHandler} 兜底，这里必须靠抛异常 + 调用方捕获来传递失败。
     *
     * @param roomId   直播间 ID（由连接身份决定）
     * @param userId   发送人 ID（由 Token 决定）
     * @param nickname 发送人昵称（服务端查库得到）
     * @param content  弹幕内容（调用方已做过非空 / 长度校验）
     * @param color    弹幕颜色（调用方已归一化为 #rgb / #rrggbb）
     * @return 可直接序列化广播的载荷
     * @throws BusinessException 命中敏感词
     */
    public DanmakuPayload checkAndBuild(Long roomId, Long userId, String nickname,
                                        String content, String color) {
        // DFA 敏感词校验：命中即拒绝。注意这是"拒绝"语义而不是"替换后放行"，
        // 因为弹幕是实时推流的，替换后的内容同样会立刻出现在所有人屏幕上。
        String hit = sensitiveWordService.check(content);
        if (hit != null) {
            throw new BusinessException("包含敏感词: " + hit);
        }

        DanmakuPayload payload = new DanmakuPayload();
        payload.setRoomId(roomId);
        payload.setUserId(userId);
        payload.setNickname(nickname);
        payload.setContent(content);
        payload.setColor(color);
        payload.setCreateTime(LocalDateTime.now());
        return payload;
    }
}
