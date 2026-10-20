package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.live.common.Result;
import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import com.live.vo.CreateLiveRoomRequest;
import com.live.vo.LiveRoomVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直播间业务逻辑：创建直播间（首次开播）、开播、关播。
 *
 * <p>核心业务规则：**一个主播同时只能有一个直播中的直播间**。
 * 所以开播/创建前，都先把该主播已有的"直播中"直播间置为下播（is_living=0），
 * 再设置新的为直播中，保证不会出现两个同时直播。
 *
 * <p>注意：这里用代码兜底实现这条规则（先关旧再开新），
 * 不依赖数据库唯一索引（因为 is_living=0 会重复，唯一索引拦不住"下播后又开播"）。
 * 这点你面试可以讲："为什么不用 UNIQUE(anchor_id, is_living) 硬卡？"
 *
 * <h2>本轮修复的三处（都会造成真实数据错误）</h2>
 * <ol>
 *   <li><b>创建直播间不再接受客户端传 id</b>（原来 {@code if (room.getId() == null)} 才生成，
 *       攻击者传别人的 roomId 就能触发"删别人的 Redis 人气 key"，见 {@link CreateLiveRoomRequest} 的类注释）；</li>
 *   <li><b>"被强制下播"的房间现在也会结算人气</b>：原来 {@link #closeOtherLiving} 只把 is_living 置 0，
 *       没调 {@code flushOnStop}，于是"开第二场时上一场被踢下线"的那一波人气直接丢在 Redis 里，
 *       {@code live_room.like_count} 永远停在开播时的 0，只有手动点"关播"才结算；</li>
 *   <li><b>Redis 操作全部移到数据库事务之外</b>（用 {@link TransactionTemplate} 而不是 {@code @Transactional}）：
 *       事务只包"改 DB"那几条 SQL；结算人气（读 Redis + 回写 DB + 删 key）在提交之后做，
 *       事务回滚就不会白白删掉 Redis 里的计数 —— 这正是项目里"Redis 不进数据库事务"那条原则。</li>
 * </ol>
 */
@Slf4j
@Service
public class LiveRoomService {

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    @Autowired
    private LiveLikeService liveLikeService;

    /**
     * 编程式事务：把事务边界收紧到"只改数据库"的那几条 SQL。
     * 这样 Redis（clearCount / flushOnStop）天然落在事务外，不会被回滚"骗"到。
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 直播间ID的毫秒内序号（配合 {@link #nextRoomId()} 使用） */
    private static final AtomicInteger ID_SEQ = new AtomicInteger();

    /**
     * 创建直播间（主播首次开播，创建后默认就是直播中）。
     *
     * @param req    前端传的直播间信息（只有 title/category/tags/cover）
     * @param userId 操作者的用户ID（来自 JWT Token）
     * @return 直播间 id
     */
    public Result<Long> create(CreateLiveRoomRequest req, Long userId) {
        // ① 入参校验：Controller 已用 @Valid 校验过，这里再兜一层（Service 也可能被别处调用）
        if (req == null) {
            return Result.fail("请求体不能为空");
        }
        if (userId == null) {
            return Result.fail("用户ID不能为空");
        }

        // ② 组装实体：**每一个字段都由服务端赋值**，客户端传什么都影响不了
        LiveRoom room = new LiveRoom();
        room.setId(nextRoomId());              // 服务端生成（原来直接采信客户端传的 id，见 CreateLiveRoomRequest）
        room.setTitle(req.getTitle().trim());
        room.setCategory(req.getCategory().trim());
        room.setTags(req.getTags() == null ? "" : req.getTags().trim());
        room.setCover(req.getCover());
        room.setAnchorId(userId);              // 主播身份来自 Token，不信前端
        room.setIsLiving(1);                   // 创建即直播中
        room.setOnlineCount(0L);               // 刚开播在线 0，由 Redis 实时统计（见 OnlineCountSyncTask）
        room.setLikeCount(0L);                 // 人气初始 0
        room.setCreateTime(LocalDateTime.now());// 开播时间由服务端决定（原来为空，前端可控制）

        // ③ 事务内：先把他其它"直播中"的房间关掉，再插入新房间
        List<Long> closedRoomIds = transactionTemplate.execute(status -> {
            List<Long> ids = closeOtherLiving(userId);
            liveRoomMapper.insert(room);
            return ids;
        });

        // ④ 事务提交之后才动 Redis：被强制下播的房间在这里结算人气
        settleClosedRooms(closedRoomIds);

        return Result.ok(room.getId());
    }

    /**
     * 开播：把指定直播间设为直播中。
     * 权限：只有该直播间的主播本人能开播。
     *
     * @param roomId 直播间ID
     * @param userId 操作者的用户ID（来自 JWT Token，不是前端传参）
     */
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

        // ① 事务内：关掉该主播其它直播中的房间 + 把自己置为直播中、人气归零
        List<Long> closedRoomIds = transactionTemplate.execute(status -> {
            List<Long> ids = closeOtherLiving(userId);
            room.setIsLiving(1);
            room.setLikeCount(0L);
            liveRoomMapper.updateById(room);
            return ids;
        });

        // ② 事务外：本场人气归零（先写库、后删 key；即使这一步失败，DB 里已经是 0，读出来仍一致）
        liveLikeService.clearCount(roomId);

        // ③ 事务外：其它被顺带下播的房间补结算（自己不算，因为自己刚刚重置为 0）
        settleClosedRooms(closedRoomIds == null ? null : closedRoomIds.stream()
                .filter(id -> !id.equals(roomId))
                .toList());

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

        // ① 事务内：只改数据库
        transactionTemplate.executeWithoutResult(status -> {
            room.setIsLiving(0);
            liveRoomMapper.updateById(room);
        });

        // ② 事务外：关播结算 —— 把本场人气写回 live_room.like_count，并清掉 Redis 计数
        liveLikeService.flushOnStop(roomId);
        return Result.ok(null);
    }

    /**
     * 直播间列表（分页）。
     *
     * 直播间推荐给用户看的是"正在直播的"，所以默认只查 is_living=1。
     * 支持按分区 category 过滤（点顶栏 Tab）。
     *
     * <p>排序说明：{@code online_count} 现在由 {@code OnlineCountSyncTask} 定期从 Redis 回写，
     * 不再是永远为 0 的死字段，所以"在线人数优先、人气次之"这条排序才真正有意义。
     *
     * @param pageNum  第几页（从 1 开始）
     * @param pageSize 每页几条
     * @param category 分区，可空（空=不过滤）
     * @return 分页结果（records 是当前页数据，total 是总数）
     */
    public Result<IPage<LiveRoomVO>> list(int pageNum, int pageSize, String category) {
        Page<LiveRoom> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<LiveRoom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LiveRoom::getIsLiving, 1);          // 只看直播中的
        if (category != null && !category.isEmpty()) {
            wrapper.eq(LiveRoom::getCategory, category);
        }
        wrapper.orderByDesc(LiveRoom::getOnlineCount)
                .orderByDesc(LiveRoom::getLikeCount);

        IPage<LiveRoom> pageResult = liveRoomMapper.selectPage(page, wrapper);
        return Result.ok(pageResult.convert(room -> LiveRoomVO.from(room, "热门")));
    }

    /**
     * 生成直播间 ID（**只由服务端生成，绝不接受客户端传入**）。
     *
     * <p>为什么不用 MyBatis-Plus 的雪花 {@code IdWorker.getId()}：雪花是 19 位数字，
     * 而 JSON 返回给浏览器后会被 JS 的 {@code Number} 截断
     * （{@code Number.MAX_SAFE_INTEGER} = 2^53 ≈ 9.007e15）—— 前端拿到的是"四舍五入后"的 id，
     * 再拼进 {@code /room.html?id=} 和 {@code /api/live/rooms/{id}/start} 就会指向另一个房间。
     * 用雪花就必须配套"Long 序列化成 String"，那要改一圈 VO 的序列化配置。
     *
     * <p>这里用「毫秒时间戳 × 1000 + 三位序号」：
     * <ul>
     *   <li>15~16 位，天然 &lt; 2^53，JS 里精确可表示（不会出现"进错房间"）；</li>
     *   <li>同一毫秒内最多 1000 个并发创建不会撞（单机演示绰绰有余）；
     *       真要做分布式，标准答案还是"雪花 ID + 接口里把 Long 序列化成字符串"。</li>
     * </ul>
     */
    private long nextRoomId() {
        int seq = Math.floorMod(ID_SEQ.getAndIncrement(), 1000);
        return System.currentTimeMillis() * 1000L + seq;
    }

    /**
     * 内部方法：把某个主播所有"直播中(is_living=1)"的直播间置为下播，并返回被关掉的 id 列表。
     *
     * <p>返回 id 是为了让调用方在**事务提交之后**给这些房间补一次人气结算
     * （{@link #settleClosedRooms}）—— 原来这里只改 is_living，人气就丢了。
     *
     * @param anchorId 主播ID
     * @return 本次被置为下播的直播间 id（可能为空列表，但不会是 null）
     */
    private List<Long> closeOtherLiving(Long anchorId) {
        List<Long> livingIds = liveRoomMapper.selectList(new LambdaQueryWrapper<LiveRoom>()
                        .select(LiveRoom::getId)
                        .eq(LiveRoom::getAnchorId, anchorId)
                        .eq(LiveRoom::getIsLiving, 1))
                .stream()
                .map(LiveRoom::getId)
                .toList();

        if (livingIds.isEmpty()) {
            return List.of();
        }
        liveRoomMapper.update(null, new LambdaUpdateWrapper<LiveRoom>()
                .eq(LiveRoom::getAnchorId, anchorId)
                .eq(LiveRoom::getIsLiving, 1)
                .set(LiveRoom::getIsLiving, 0));
        return livingIds;
    }

    /**
     * 给"被强制下播"的房间补结算人气（在事务提交之后调用）。
     *
     * <p>为什么每个房间单独 try/catch：结算只是补偿动作，
     * 某个房间结算失败不能让"创建/开播"这个主流程报错（那才是用户真正关心的结果）。
     */
    private void settleClosedRooms(List<Long> closedRoomIds) {
        if (closedRoomIds == null || closedRoomIds.isEmpty()) {
            return;
        }
        for (Long id : closedRoomIds) {
            try {
                liveLikeService.flushOnStop(id);
            } catch (Exception e) {
                log.warn("被强制下播的房间 {} 人气结算失败（不影响主流程）", id, e);
            }
        }
    }
}
