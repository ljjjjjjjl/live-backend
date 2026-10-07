package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.live.common.BusinessException;
import com.live.entity.Danmaku;
import com.live.mapper.DanmakuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 弹幕业务逻辑：
 *   1) 保存弹幕（事务：插入 + 后续广播由 WebSocket 层做）
 *   2) 拉取某直播间历史弹幕（分页，最新在前）
 *
 * 面试点（为什么 save 加事务）：弹幕落库是"一条写"，单条 SQL 本身是原子的，
 * 但这里用 @Transactional 是为将来扩展留口子——比如"先写库 + 同时改在线人数/统计字段"
 * 这类多写场景，事务能保证"要么全成，要么全回滚"。单条插入加不加其实都可，加了无害。
 */
@Service
public class DanmakuService {

    @Autowired
    private DanmakuMapper danmakuMapper;

    @Autowired
    private SensitiveWordService sensitiveWordService;

    /**
     * 保存一条弹幕到数据库（入库前先做 DFA 敏感词校验）。
     * 广播给直播间内所有在线用户的动作，由 WebSocketHandler 负责（不在这里），
     * 本方法只负责"持久化"这一件事，职责单一。
     *
     * 敏感词校验：命中敏感词 → 抛 BusinessException，由 GlobalExceptionHandler 统一返回
     * {code:1, msg:包含敏感词...}。过滤策略在这里可改成"替换后放行"（用 filter）或"拒绝"（抛异常）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Danmaku save(Danmaku danmaku) {
        // DFA 敏感词校验：命中即拒绝（走全局异常返回统一提示）
        String hit = sensitiveWordService.check(danmaku.getContent());
        if (hit != null) {
            throw new BusinessException("包含敏感词: " + hit);
        }
        danmakuMapper.insert(danmaku);
        return danmaku;
    }

    /**
     * 拉取某直播间历史弹幕（分页）。
     *
     * 索引：idx_room_time(room_id, id) 正好覆盖这个查询：
     *   WHERE room_id = ? 走最左匹配，ORDER BY id DESC 走索引顺序，
     *   所以既能过滤又能排序，不用 filesort。
     *
     * @param roomId   直播间ID
     * @param pageNum  第几页（从 1 开始）
     * @param pageSize 每页几条
     * @return 分页结果（records 当前页，total 总数）
     */
    public IPage<Danmaku> history(Long roomId, int pageNum, int pageSize) {
        Page<Danmaku> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Danmaku> wrapper = new LambdaQueryWrapper<Danmaku>()
                .eq(Danmaku::getRoomId, roomId)
                .orderByDesc(Danmaku::getId);          // 最新的在前
        return danmakuMapper.selectPage(page, wrapper);
    }
}
