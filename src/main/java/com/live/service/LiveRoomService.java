package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.live.common.Result;
import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import com.live.vo.LiveRoomVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 直播间业务逻辑：创建直播间（首次开播）、开播、关播。
 *
 * 核心业务规则：**一个主播同时只能有一个直播中的直播间**。
 * 所以开播/创建前，都先把该主播已有的"直播中"直播间置为下播（is_living=0），
 * 再设置新的为直播中，保证不会出现两个同时直播。
 *
 * 注意：这里用代码兜底实现这条规则（先关旧再开新），
 * 不依赖数据库唯一索引（因为 is_living=0 会重复，唯一索引拦不住"下播后又开播"）。
 * 这点你面试可以讲："为什么不用 UNIQUE(anchor_id, is_living) 硬卡？"
 */
@Service
public class LiveRoomService {

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    @Autowired
    private LiveLikeService liveLikeService;

    /**
     * 创建直播间（主播首次开播，创建后默认就是直播中）。
     *
     * @param room   前端传的直播间信息（title/category/tags；anchorId 由 Token 覆盖，不信前端）
     * @param userId 操作者的用户ID（来自 JWT Token）
     * @return 直播间 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Result<Long> create(LiveRoom room, Long userId) {
        // ① 入参校验：标题必须有
        if (room == null) {
            return Result.fail("请求体不能为空");
        }
        if (room.getTitle() == null || room.getTitle().trim().isEmpty()) {
            return Result.fail("直播标题不能为空");
        }
        if (userId == null) {
            return Result.fail("用户ID不能为空");
        }

        // ② 主播身份来自 Token，不信任前端传的 anchorId（防止冒充别人建播）
        room.setAnchorId(userId);

        // ③ 业务兜底：先把该主播已有的"直播中"直播间关掉，保证同时只开一个
        closeOtherLiving(userId);

        // ④ 设置新直播间的默认值并插入
        room.setIsLiving(1);          // 创建即直播中
        room.setOnlineCount(0L);      // 刚开播在线 0，由 Redis/WebSocket 实时累加
        room.setLikeCount(0L);        // 人气初始 0
        // 演示数据用手工 id；真实项目在实体上用 @TableId(type = IdType.ASSIGN_ID) 走雪花生成
        if (room.getId() == null) {
            room.setId(System.currentTimeMillis());
        }
        // 人气从 0 开始：清掉 Redis 里可能残留的同 id 计数（先清 Redis、再写库，见 LiveLikeService#clearCount）
        liveLikeService.clearCount(room.getId());
        liveRoomMapper.insert(room);
        return Result.ok(room.getId());
    }

    /**
     * 开播：把指定直播间设为直播中。
     * 权限：只有该直播间的主播本人能开播。
     *
     * @param roomId 直播间ID
     * @param userId 操作者的用户ID（来自 JWT Token，不是前端传参）
     */
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> start(Long roomId, Long userId) {
        if (roomId == null || userId == null) {
            return Result.fail("直播间ID和用户ID不能为空");
        }
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) {
            return Result.fail("直播间不存在");
        }
        // 权限校验：只有本人能开自己的播
        if (!room.getAnchorId().equals(userId)) {
            return Result.fail("无权操作该直播间");
        }
        // ① 本场人气归零：先清 Redis（万一后面回滚，读出来会回退到数据库旧值，前后一致）
        liveLikeService.clearCount(roomId);
        // ② 开播前，把该主播其他直播中的直播间关掉（保证同时只开一个）
        closeOtherLiving(userId);
        // ③ 置为直播中 + 本场人气从 0 开始（同一条 update 写库）
        room.setIsLiving(1);
        room.setLikeCount(0L);
        liveRoomMapper.updateById(room);
        return Result.ok(null);
    }

    /**
     * 关播：把指定直播间设为下播。
     * 权限：只有该直播间的主播本人能关播。
     */
    public Result<Void> stop(Long roomId, Long userId) {
        if (roomId == null || userId == null) {
            return Result.fail("直播间ID和用户ID不能为空");
        }
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) {
            return Result.fail("直播间不存在");
        }
        if (!room.getAnchorId().equals(userId)) {
            return Result.fail("无权操作该直播间");
        }
        room.setIsLiving(0);
        liveRoomMapper.updateById(room);
        // 关播结算：把本场人气写回 live_room.like_count，并清掉 Redis 计数（见 LiveLikeService#flushOnStop）
        liveLikeService.flushOnStop(roomId);
        return Result.ok(null);
    }

    /**
     * 直播间列表（分页）。
     *
     * 直播间推荐给用户看的是"正在直播的"，所以默认只查 is_living=1。
     * 支持按分区 category 过滤（点顶栏 Tab）。
     * 支持页码分页（第 2 周要做的"直播间列表接口（分页 + 索引优化）"就是这个）。
     *
     * 为什么用分页插件而不是自己写 LIMIT？——配合 MybatisPlusConfig 里注册的分页插件，
     * IPage 会自动生成物理分页（LIMIT），并返回 total/current/size 等分页信息。
     *
     * @param pageNum  第几页（从 1 开始）
     * @param pageSize 每页几条
     * @param category 分区，可空（空=不过滤）
     * @return 分页结果（records 是当前页数据，total 是总数）
     */
    public Result<IPage<LiveRoomVO>> list(int pageNum, int pageSize, String category) {
        // ① 构造分页对象：第几页 + 每页几条
        Page<LiveRoom> page = new Page<>(pageNum, pageSize);

        // ② 构造查询条件
        LambdaQueryWrapper<LiveRoom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LiveRoom::getIsLiving, 1);          // 只看直播中的
        // 分区过滤（可空：传了才加这个条件）
        if (category != null && !category.isEmpty()) {
            wrapper.eq(LiveRoom::getCategory, category);
        }
        // ③ 排序：在线人数多的排前面（直播热度），在线相同再按人气
        wrapper.orderByDesc(LiveRoom::getOnlineCount)
                .orderByDesc(LiveRoom::getLikeCount);

        // ④ 执行分页查询（需要分页插件，见 MybatisPlusConfig）
        IPage<LiveRoom> pageResult = liveRoomMapper.selectPage(page, wrapper);

        // ⑤ 把实体转成 VO 再返回（带 reason="热门" 等，也可不带，这里统一转）
        IPage<LiveRoomVO> voPage = pageResult.convert(room -> LiveRoomVO.from(room, "热门"));

        return Result.ok(voPage);
    }

    /**
     * 内部方法：把某个主播所有"直播中(is_living=1)"的直播间全部置为下播。
     * 用 MyBatis-Plus 的 update + LambdaUpdateWrapper 批量更新，无需查出再逐条改。
     *
     * @param anchorId 主播ID
     */
    private void closeOtherLiving(Long anchorId) {
        liveRoomMapper.update(null, new LambdaUpdateWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, anchorId)
                .eq(LiveRoom::getIsLiving, 1)
                .set(LiveRoom::getIsLiving, 0));
    }
}
