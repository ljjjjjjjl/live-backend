package com.live.controller;

import com.live.common.Result;
import com.live.entity.LiveRoom;
import com.live.mapper.LiveRoomMapper;
import com.live.service.LiveLikeService;
import com.live.service.RedisRankService;
import com.live.service.RoomExistsCache;
import com.live.vo.LiveLikeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 直播间实时互动接口（第 3 周 Redis：礼物榜 + 在线人数）。
 *
 * <ul>
 *   <li>POST /api/live/{roomId}/like             —— 点赞（人气 +delta，Redis INCRBY；可连点、不可取消）</li>
 *   <li>GET  /api/live/{roomId}/like             —— 当前人气（Redis 优先，回退 DB）</li>
 *   <li>POST /api/live/{roomId}/gift             —— 送礼物：给主播累加礼物价值（进礼物榜 ZSet）</li>
 *   <li>GET  /api/live/{roomId}/gift/rank?top=10 —— 直播间礼物榜 TopN（ZREVRANGE）</li>
 *   <li>POST /api/live/{roomId}/enter             —— 进入直播间：在线人数 +1（Set）</li>
 *   <li>POST /api/live/{roomId}/leave             —— 离开直播间：在线人数 -1（Set）</li>
 *   <li>GET  /api/live/{roomId}/online            —— 当前在线人数（SCARD）</li>
 * </ul>
 *
 * <h2>⭐ 身份一律取 Token（2026-10-07 修复越权口子）</h2>
 * <p>改造前：enter/leave 从 query 取 {@code userId}、gift 直接采信前端传的 {@code receiverId}。
 * 后果是任何人只要填一个别人的 id，就能<b>替别人进房/离房</b>（刷在线人数）、
 * 或者<b>把礼物记到任意用户名下</b> —— 而开播/关播/发弹幕早就是"只认 Token"，
 * 同一套系统两种口径，属于明显的越权漏洞。
 *
 * <p>修复后：
 * <ul>
 *   <li><b>操作者是谁</b>（进房/离房/送礼的人）→ 一律 {@code @RequestAttribute("userId")}，
 *       由 JWT 拦截器从 Token 解析后放进 request，前端传什么都不影响判定；</li>
 *   <li><b>礼物送给谁</b>（{@code receiverId}）→ 由<b>房间自己推导</b>（收礼者就是这个房间的主播
 *       {@code live_room.anchor_id}）。前端仍可传，但服务端会校验必须与房间主播一致，否则拒绝。</li>
 * </ul>
 *
 * <p>一句话原则：<b>凡是服务端能推导出来的身份和目标，都不该由前端决定。</b>
 *
 * <p>说明：这些是"实时读 + 异步落库"的实时通道。礼物/在线以 Redis 为准（O(1)/O(logN)），
 * 最终靠"落库 + 对账"保证和数据库一致（最终一致）。生产会把"送礼物"异步落库到 MySQL 礼品流水表。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/live/{roomId}")
public class LiveInteractiveController {

    /** 单次送礼的价值上限（演示值）：防止一个客户端把榜单永久顶死，也防溢出 */
    private static final double MAX_GIFT_VALUE = 1_000_000D;

    @Autowired
    private RedisRankService redisRankService;

    @Autowired
    private LiveLikeService liveLikeService;

    @Autowired
    private LiveRoomMapper liveRoomMapper;

    @Autowired
    private RoomExistsCache roomExistsCache;

    /**
     * 直播间点赞（人气）：把 delta 累加到 {@code live:like:{roomId}}。
     *
     * <p>语义：<b>本场人气</b> —— 不判重、不取消，可以一直点（业务上就是"直播间有多热"）。
     * 前端会本地攒批、每 500ms 合并上报一次，所以这里的 delta 通常 &gt; 1。
     *
     * <p>防刷靠<b>限速</b>而不是去重：同一用户在同一房间每秒最多
     * {@link LiveLikeService#RATE_LIMIT_PER_SECOND} 次上报，超了返回业务失败但不报错码。
     *
     * @return 最新人气；被限速时 code=1
     */
    @PostMapping("/like")
    public Result<Long> like(@PathVariable Long roomId,
                             @RequestBody(required = false) LiveLikeRequest body,
                             @RequestAttribute("userId") Long userId) {
        // ① delta 缺省 1（单次点赞），并且必须落在合理区间（防止有人传 1 个亿把人气刷爆）
        int delta = (body == null || body.getDelta() == null) ? 1 : body.getDelta();
        if (delta < 1 || delta > LiveLikeService.MAX_DELTA) {
            return Result.fail("delta 必须在 1~" + LiveLikeService.MAX_DELTA + " 之间");
        }
        // ② 房间必须存在（Redis 里凭空多一个房间的 key 没有意义，也没人能看到它）
        //    ⚠️ 这里走本地缓存，不是每次都查库 —— 压测实测：这条 selectById 让单请求从 1.58ms
        //       涨到 5.97ms，而 MySQL 自己执行它是 0.52ms，多出来的 4ms 全在"取连接 + MyBatis 映射"。
        //       它同时也是整个接口的吞吐瓶颈（无论并发开到 50 还是 600，吞吐都卡在 1250 左右）。
        //       房间存在性是典型读多写少，缓存 60 秒即可，具体取舍见 RoomExistsCache 的类注释。
        if (!roomExistsCache.exists(roomId)) {
            return Result.fail("直播间不存在");
        }
        // ③ 限速 + 累加在同一次 Lua 里完成
        long count = liveLikeService.like(roomId, userId, delta);
        if (count < 0) {
            return Result.fail("点赞太快了，慢一点～");
        }
        return Result.ok(count);
    }

    /** 当前人气（游客可读）：Redis 优先，key 不存在时回退数据库的结算值 */
    @GetMapping("/like")
    public Result<Long> likeCount(@PathVariable Long roomId) {
        return Result.ok(liveLikeService.currentCount(roomId));
    }

    /**
     * 送礼物：给<b>本房间的主播</b>累加礼物价值。
     *
     * @param senderId   送礼者（来自 Token，前端不可伪造）
     * @param receiverId 收礼者：可省略（默认取本房间主播）；传了就必须等于房间主播，否则拒绝
     * @param value      礼物价值，必须 &gt; 0（防止负数把榜单刷下去）
     */
    @PostMapping("/gift")
    public Result<Void> gift(@PathVariable Long roomId,
                             @RequestParam(required = false) Long receiverId,
                             @RequestParam(defaultValue = "100") double value,
                             @RequestAttribute("userId") Long senderId) {
        // ① 直播间必须存在，否则礼物会记到一个根本不存在的房间上
        LiveRoom room = liveRoomMapper.selectById(roomId);
        if (room == null) {
            return Result.fail("直播间不存在");
        }
        // ② 收礼者 = 本房间主播：前端传了就校验，不传就用服务端推导的值 —— 总之以服务端为准
        Long anchorId = room.getAnchorId();
        if (receiverId != null && !receiverId.equals(anchorId)) {
            return Result.fail("收礼者不是本房间主播");
        }
        // ③ 价值必须是有限正数：既要挡负数（把榜单刷下去），也要挡 NaN/Infinity
        //    （double 的 NaN 和任何数比较都是 false，`value <= 0` 拦不住它；
        //     而 Redis 的 ZINCRBY 收到 NaN 会直接报错，等于把一个客户端参数变成了服务端异常）
        if (!Double.isFinite(value) || value <= 0) {
            return Result.fail("礼物价值必须是大于 0 的有限数");
        }
        if (value > MAX_GIFT_VALUE) {
            return Result.fail("礼物价值不能超过 " + (long) MAX_GIFT_VALUE);
        }
        // 生产：还要校验礼物是否存在、送礼者余额是否充足；并异步落库礼品流水表
        redisRankService.addGift(roomId, anchorId, value);
        log.info("用户 {} 在直播间 {} 送出价值 {} 的礼物（收礼主播 {}）", senderId, roomId, value, anchorId);
        return Result.ok(null);
    }

    /** 直播间礼物榜 TopN（按礼物价值降序） */
    @GetMapping("/gift/rank")
    public Result<List<Map<String, Object>>> giftRank(@PathVariable Long roomId,
                                                      @RequestParam(defaultValue = "10") int top) {
        return Result.ok(redisRankService.giftTopN(roomId, top));
    }

    /** 进入直播间：在线人数 +1。userId 取 Token，不接受前端传参（否则能替别人刷在线） */
    @PostMapping("/enter")
    public Result<Void> enter(@PathVariable Long roomId, @RequestAttribute("userId") Long userId) {
        redisRankService.online(roomId, userId);
        return Result.ok(null);
    }

    /**
     * 在线心跳：前端每 20 秒打一次，表示"我还在这间房里"。
     *
     * <p>为什么需要心跳？在线集合里的每个成员都带一个"最后活跃时间"，
     * 超过 {@link RedisRankService#ONLINE_TTL_MS} 没有心跳就会被自动剔除。
     * 这样即使客户端崩溃/断网/被强杀（根本发不出"离开"请求），在线人数也不会只增不减。
     *
     * <p>语义上它就是 {@code /enter}（幂等的 ZADD），单独开一个路径是为了让前端代码更直观。
     */
    @PostMapping("/heartbeat")
    public Result<Void> heartbeat(@PathVariable Long roomId, @RequestAttribute("userId") Long userId) {
        redisRankService.online(roomId, userId);
        return Result.ok(null);
    }

    /** 离开直播间：在线人数 -1。userId 同上，一律取 Token */
    @PostMapping("/leave")
    public Result<Void> leave(@PathVariable Long roomId, @RequestAttribute("userId") Long userId) {
        redisRankService.offline(roomId, userId);
        return Result.ok(null);
    }

    /** 当前在线人数 */
    @GetMapping("/online")
    public Result<Long> online(@PathVariable Long roomId) {
        return Result.ok(redisRankService.onlineCount(roomId));
    }
}
