package com.live.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.live.common.BusinessException;
import com.live.entity.UserLike;
import com.live.entity.Video;
import com.live.mapper.UserLikeMapper;
import com.live.mapper.VideoMapper;
import com.live.vo.LikeResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 视频点赞业务（第 4 周核心功能）。
 *
 * <h2>一、数据分层：权威 vs 派生（本节最重要）</h2>
 * <pre>
 *   user_like 表          ← 权威（Source of Truth）：谁给谁点了赞
 *         │ 聚合
 *         ▼
 *   video.like_count      ← 派生：有多少个赞（可重算，允许延迟）
 *         │ 加速读
 *         ▼
 *   Redis video:like:count:{videoId}  ← 派生缓存：列表页展示用的实时数字
 * </pre>
 * <b>为什么必须拆开？</b>
 * <ul>
 *   <li>{@code user_like} 丢了就<b>永远算不回来</b>（"谁点的赞"这个事实没了）→ 所以它必须
 *       <b>同步写、强一致</b>，并且靠唯一索引 {@code uk_user_video} 保证不重复；</li>
 *   <li>{@code like_count} 丢了可以 {@code SELECT COUNT(*)} 重算 → 所以它可以<b>异步、可延迟</b>，
 *       走 Redis + MQ 异步刷回，把高频写压力从 MySQL 上卸掉。</li>
 * </ul>
 * 一句话：<b>把「不可丢的」和「可重算的」分开对待，是缓存/异步设计的通用套路。</b>
 *
 * <h2>二、幂等与并发（防重复计数）</h2>
 * 点赞最怕"同一个人点两次算两个赞"。这里用了<b>两道防线</b>：
 * <ol>
 *   <li><b>应用层预检</b>（{@code selectCount}）：拦掉绝大多数重复请求，好处是能给出友好提示，
 *       且不产生异常开销；</li>
 *   <li><b>数据库唯一索引</b>（{@code uk_user_video}）：<b>真正兜底的原子保证</b>。
 *       因为"先查再插"之间存在 TOCTOU 竞态窗口（两个请求同时查到"没点过"），
 *       最终由数据库唯一索引拦下，抛 {@link DuplicateKeyException} —— 我们捕获它并当作"已点赞"处理。</li>
 * </ol>
 * 取消点赞同理用<b>行数门控</b>：只有 {@code DELETE} 真的删掉了行（影响行数 &gt; 0）才递减计数，
 * 否则连点两次取消会把计数减成负数。
 *
 * <h2>三、缓存一致性</h2>
 * Redis 数字只是<b>展示值</b>，读不到时从权威表 {@code user_like} 重算并回填（Cache Aside）。
 * 注意这里<b>不从 {@code video.like_count} 重算</b> —— 因为它可能还没被异步刷回，是旧的。
 *
 * <h2>四、事务边界（2026-09-14 改造，务必守住）</h2>
 * <b>事务里只允许出现数据库操作，绝不允许出现 Redis / MQ 等外部 IO。</b>
 * <pre>
 *   ❌ 改造前：like/unlike 整个方法挂 @Transactional
 *        ── 事务一开，Hikari 连接就被借走并<b>一直占着</b>；方法里再调 Redis，
 *           Redis 抖 3 秒 → 数据库连接就被白占 3 秒 → 连接池（默认只有 10）迅速耗尽 → 雪崩。
 *   ✅ 改造后：只有"写权威表"那一条 SQL 包在 TransactionTemplate 里，
 *        Redis 读写（捂热 / applyDelta / getLikeCount）与 MQ 投递全部在事务<b>提交之后</b>。
 * </pre>
 * 这样还顺带拿到一个语义收益：<b>DB 回滚了，Redis 就不会多加那一个赞</b>
 * （改造前是先加 Redis、再提交，中途失败会留下偏差）。
 *
 * <p>为什么用 {@link TransactionTemplate} 而不是把方法拆到另一个 {@code @Transactional} Bean 里？
 * 因为 Spring 的 {@code @Transactional} 靠代理生效，<b>同一个类里自调用不走代理、事务会失效</b>；
 * {@code TransactionTemplate} 是编程式事务，写法显式、不受自调用限制，边界一眼可见。
 */
@Slf4j
@Service
public class VideoLikeService {

    /**
     * Redis 点赞计数 key 前缀。
     *
     * <p>为什么不复用 {@code CountCacheService} 的 {@code live:count:{targetId}}？
     * 因为那个 key 里只有 targetId、没有"目标类型"，视频 101 和直播间 101 会撞在同一个 Hash 上，
     * 计数互相污染。所以这里用带类型前缀的独立命名空间：{@code video:like:count:{videoId}}。
     */
    private static final String LIKE_COUNT_KEY = "video:like:count:";

    /** 供对账服务复用（避免 key 命名散落在两个类里）。对外只暴露这个常量，不暴露 Redis 操作细节 */
    public static String countKey(Long videoId) {
        return LIKE_COUNT_KEY + videoId;
    }

    /** 「我点过赞的视频」最多返回多少条（见 {@link #likedVideoIds} 的说明） */
    private static final int LIKED_IDS_LIMIT = 500;

    /**
     * 「存在则原子累加、不存在则让调用方去重建」的 Lua 脚本。
     *
     * <p><b>为什么必须用 Lua？</b> 原来的写法是 Java 里分两步：
     * <pre>
     *   if (hasKey(key)) increment(key, delta); else rebuild(key);
     * </pre>
     * {@code hasKey} 和 {@code increment} 之间是两次独立往返，中间 key 一旦消失，
     * 就会走错分支（或对空 key 从 0 起算）。Lua 在 Redis 单线程里执行，
     * 「判断 + 累加」是一次原子操作，窗口被彻底消除。
     *
     * <p>返回 -1 = key 不存在，交给 {@link #applyDelta} 用数据库真值回填。
     * （计数不可能为负：取消点赞有"行数门控"，见 {@link #unlike}，所以 -1 可安全当哨兵。）
     */
    private static final DefaultRedisScript<Long> INCR_IF_EXISTS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    /**
     * 计数缓存的过期策略：<b>永不过期（不设 TTL）</b>。
     *
     * <p><b>为什么计数类缓存不能设 TTL？</b>（这里原先是 {@code Duration.ofHours(24)}，已废弃）
     * <ul>
     *   <li><b>热 key 被不断续期 → 永不过期</b>：原来的 {@code applyDelta} 每次写操作都 {@code expire}，
     *       导致最热的视频 key 反而永远不过期 —— 一旦计数算错，就<b>长期错下去</b>；</li>
     *   <li><b>冷 key 过期 → 缓存击穿</b>：没人看的视频 key 一过期，恰好来一波请求，就同时去
     *       {@code SELECT COUNT(*)} 重建，把 MySQL 打死；</li>
     *   <li>逻辑正好搞反了：<b>该自愈的（热 key）不自愈，不该有流量的（冷 key）反而集中打库</b>。</li>
     * </ul>
     *
     * <p>而计数数据本身<b>量小</b>（一个视频一个 key，值是个数字）、<b>可重算</b>
     * （丢了从权威表 {@code user_like} {@code COUNT(*)} 重建）—— TTL 对它<b>只有风险没有收益</b>。
     *
     * <p>⚠️ <b>配套要求</b>：不设 TTL 意味着内存只增不减，必须靠 Redis 服务端的内存淘汰策略兜底，
     * 配 {@code maxmemory-policy allkeys-lru}（见 {@code application.yml} 的 Redis 段注释）。
     * 注意不能用 {@code volatile-lru} —— 它只淘汰"设了 TTL 的 key"，而这些 key 恰恰都没有 TTL，
     * 结果就是内存写满后直接报错。
     */

    @Autowired
    private UserLikeMapper userLikeMapper;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 异步投递出口（当前是 MQ 占位实现，见 {@link LikeCountPublisher}） */
    @Autowired
    private LikeCountPublisher likeCountPublisher;

    /**
     * 编程式事务：把事务边界精确地限制在"写权威表"那一条 SQL 上。
     *
     * <p>由 Spring Boot 自动配置提供（见 {@code TransactionAutoConfiguration}），
     * 只要容器里有一个 {@code PlatformTransactionManager} 就能直接注入。
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 对账服务：权威表一变就 {@code SADD like:dirty:video} 标脏，
     * 由 {@code LikeCountReconcileTask} 定期把这批脏视频的 {@code video.like_count} 校正回来。
     */
    @Autowired
    private LikeReconcileService likeReconcileService;

    /* ==================================================================
       点赞 / 取消点赞
       ================================================================== */

    /**
     * 点赞（幂等）。
     *
     * <p>流程：校验视频 → 预检是否已赞 → 权威表插入（唯一索引兜底）→ 只有"真正新增"才 +1。
     *
     * <p><b>事务边界</b>：本方法<b>不带 {@code @Transactional}</b>，只有第 ③ 步的 INSERT
     * 包在 {@link TransactionTemplate} 里；Redis（第 ②④ 步）与 MQ 投递全在事务外。
     *
     * @param userId  点赞人（来自 JWT，不信任前端传参）
     * @param videoId 被点赞的视频
     * @return 操作后的状态与最新总数
     */
    public LikeResultVO like(Long userId, Long videoId) {
        requireVideoExists(videoId);

        // ① 应用层预检：已点过就直接返回（幂等），避免走异常路径
        if (exists(userId, videoId)) {
            log.debug("用户 {} 重复点赞视频 {}，幂等返回", userId, videoId);
            return new LikeResultVO(true, getLikeCount(videoId));
        }

        // ② 先把缓存"捂热"。⚠️ 必须在改数据库【之前】做！
        //    因为重建计数靠的是 SELECT COUNT(*)：如果放到 INSERT 之后重建，
        //    那次 COUNT 已经把新插入的记录算进去了，再叠加 +1 就变成【重复计数】。
        //    它内部含 Redis 写，所以同样放在事务外。
        warmCountIfAbsent(videoId);

        // ③ ★ 唯一的事务范围：只有这一条 INSERT（详见类注释「四、事务边界」）
        Boolean inserted = transactionTemplate.execute(status -> insertLikeRecord(userId, videoId));

        // ④ 只有真正新增才 +1 —— 这是"不重复计数"的关键。
        //    ⚠️ 事务已提交，这里才动 Redis / MQ：DB 若回滚就不会多加一个赞，
        //       而 Redis 的网络抖动也不会占着数据库连接。
        //    ⑤ 顺手把 videoId 标进"脏集合"：派生列 video.like_count 现在肯定和权威表对不上了，
        //       交给 LikeCountReconcileTask 定点修正（不用再全表扫）。
        if (Boolean.TRUE.equals(inserted)) {
            applyDelta(videoId, 1L);
            likeCountPublisher.publishVideoLikeDelta(videoId, 1L);
            likeReconcileService.markDirty(videoId);
        }

        return new LikeResultVO(true, getLikeCount(videoId));   // 读 Redis，仍在事务外
    }

    /**
     * 落库那一步（在 {@link TransactionTemplate} 里被调用）。
     *
     * <p>并发下预检可能同时通过 → 由唯一索引 {@code uk_user_video} 兜底。
     * 异常必须在<b>回调内部</b>捕获：若抛到 {@code TransactionTemplate} 之外，
     * 事务会被标记成 rollback-only。
     *
     * @return {@code true} = 本次真的新增了一条（需要 +1）；{@code false} = 命中唯一索引，已点过
     */
    private boolean insertLikeRecord(Long userId, Long videoId) {
        try {
            UserLike record = new UserLike();
            record.setUserId(userId);
            record.setVideoId(videoId);
            record.setCreateTime(LocalDateTime.now());
            userLikeMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            // 竞态：另一个请求抢先插入了。视为已点赞，绝不重复计数。
            // 注意：MySQL 的 InnoDB 在唯一键冲突后不会让整个事务失效，捕获后可以继续（PG 则不行）。
            log.debug("用户 {} 点赞视频 {} 命中唯一索引（并发重复），按已点赞处理", userId, videoId);
            return false;
        }
    }

    /**
     * 取消点赞（幂等）。
     *
     * <p>关键在<b>行数门控</b>：先删除权威表记录，只有影响行数 &gt; 0 才递减计数。
     * 如果不管行数直接 -1，用户连点两次取消就会把计数减成负数。
     *
     * <p><b>事务边界</b>：同 {@link #like} —— 只有 DELETE 那条 SQL 在事务里，
     * 行数门控的判断与 Redis 操作都在事务<b>提交之后</b>做。
     */
    public LikeResultVO unlike(Long userId, Long videoId) {
        // ⚠️ 本轮修复：这里原来**没有** requireVideoExists。
        //    后果很实际：unlike 走不到 1 行删除，但仍会执行 warmCountIfAbsent → rebuildCount，
        //    而 rebuildCount 是「SELECT COUNT(*) 然后无条件 SET video:like:count:{id}」，
        //    这个 key **没有 TTL**。于是任何一个登录用户只要遍历 id 调
        //    DELETE /api/video/{随便一个不存在的id}/like，就能在 Redis 里凭空造出
        //    无数个永不删除的 key（内存打满），顺便把"不存在的视频"也写进缓存。
        //    对称性检查：like() 有 requireVideoExists，unlike() 必须有。
        requireVideoExists(videoId);

        // 同样要"变更前捂热"：若在 DELETE 之后才重建，COUNT 已经少了一条，再 -1 就是【重复递减】
        warmCountIfAbsent(videoId);

        // ★ 唯一的事务范围：只有这一条 DELETE
        Integer deleted = transactionTemplate.execute(status -> userLikeMapper.delete(
                new LambdaQueryWrapper<UserLike>()
                        .eq(UserLike::getUserId, userId)
                        .eq(UserLike::getVideoId, videoId)));

        // 事务外：拿提交后的真实影响行数做"行数门控"，再动 Redis / MQ
        if (deleted != null && deleted > 0) {
            applyDelta(videoId, -1L);
            likeCountPublisher.publishVideoLikeDelta(videoId, -1L);
            likeReconcileService.markDirty(videoId);   // 派生列可能对不上了，标脏交给对账
        } else {
            log.debug("用户 {} 取消点赞视频 {} 时并无点赞记录，幂等返回", userId, videoId);
        }

        return new LikeResultVO(false, getLikeCount(videoId));
    }

    /* ==================================================================
       查询
       ================================================================== */

    /** 当前用户是否已点赞该视频 */
    public boolean isLiked(Long userId, Long videoId) {
        return exists(userId, videoId);
    }

    /**
     * 查询点赞总数（Cache Aside）。
     *
     * <p>缓存未命中时，从<b>权威表 user_like</b> 重算并回填，而不是读 {@code video.like_count}
     * —— 因为后者是异步刷回的派生值，可能是旧的。
     */
    public long getLikeCount(Long videoId) {
        String key = LIKE_COUNT_KEY + videoId;

        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException e) {
                // 缓存里被写入了非法值（人为误操作等）→ 删掉当未命中处理，别让脏值一直返回
                log.warn("点赞计数缓存值非法，key={}, value={}，已清除", key, cached);
                stringRedisTemplate.delete(key);
            }
        }
        return rebuildCount(videoId);
    }

    /**
     * 当前用户点过赞的所有视频 ID（供列表页标记"已赞"状态，避免逐条查询）。
     *
     * <p>本轮修复：这里原来是<b>无 LIMIT 的全量 selectList</b>，一个重度用户点了几万个赞时，
     * 一次请求就要把几万行读出来、序列化成 JSON 返回。现在：
     * <ul>
     *   <li>只 {@code select(videoId)}，不把整行实体读出来（少读一堆用不到的列）；</li>
     *   <li>按 {@code create_time DESC} 取最近 {@value #LIKED_IDS_LIMIT} 条，走 {@code idx_user_time}
     *       —— 列表页只需要"最近赞过的"，历史久远的赞在首屏判断里意义也不大；</li>
     *   <li>语义变化写在这里：返回的是"最近的 500 个已赞 ID"。</li>
     * </ul>
     */
    public List<Long> likedVideoIds(Long userId) {
        return userLikeMapper.selectList(new LambdaQueryWrapper<UserLike>()
                        .select(UserLike::getVideoId)
                        .eq(UserLike::getUserId, userId)
                        .orderByDesc(UserLike::getCreateTime)
                        .last("limit " + LIKED_IDS_LIMIT))
                .stream()
                .map(UserLike::getVideoId)
                .collect(Collectors.toList());
    }

    /* ==================================================================
       内部方法
       ================================================================== */

    private boolean exists(Long userId, Long videoId) {
        Long cnt = userLikeMapper.selectCount(new LambdaQueryWrapper<UserLike>()
                .eq(UserLike::getUserId, userId)
                .eq(UserLike::getVideoId, videoId));
        return cnt != null && cnt > 0;
    }

    /**
     * 从权威表 user_like 重算并回填 Redis。
     *
     * <p>⚠️ <b>不带 TTL</b>（原来写的是 {@code set(key, value, COUNT_TTL)}，2026-09-14 已去掉）：
     * 见 {@link #INCR_IF_EXISTS_SCRIPT} 上方的「为什么计数类缓存不能设 TTL」。
     */
    private long rebuildCount(Long videoId) {
        Long cnt = userLikeMapper.selectCount(new LambdaQueryWrapper<UserLike>()
                .eq(UserLike::getVideoId, videoId));
        long value = cnt == null ? 0L : cnt;
        stringRedisTemplate.opsForValue().set(LIKE_COUNT_KEY + videoId, String.valueOf(value));
        return value;
    }

    /**
     * 缓存未命中时，用「<b>当前</b>数据库真实计数」把 Redis 计数捂热。
     *
     * <p>⚠️ <b>必须在修改数据库之前调用！</b> 这是一个非常容易踩的坑：
     * 重建计数靠的是 {@code SELECT COUNT(*)}，如果等 INSERT/DELETE 做完再重建，
     * 那次 COUNT 反映的已经是<b>改完之后</b>的数字，再叠加 ±1 就会：
     * <ul>
     *   <li>点赞时 → 重复计数（多算 1）</li>
     *   <li>取消时 → 重复递减（少算 1）</li>
     * </ul>
     * 这个偏差一旦留下就很难发现 —— 属于"测试不仔细就看不出来"的隐蔽 bug。
     *
     * <p>那为什么还要"捂热"而不是让 {@code INCRBY} 自己从 0 开始？因为 {@code INCRBY} 对
     * <b>不存在的 key 会从 0 起算</b>：某视频真实有 5 个赞，缓存缺失时一个人点赞，
     * 直接 INCR 会得到 1 而不是 6。
     *
     * <p>和 {@link #applyDelta} 的分工（"快路径 + 慢路径"）：
     * 这里是<b>常态快路径</b>，保证 key 存在、且基准值正确；
     * {@code applyDelta} 里 Lua 返回 -1 的分支是<b>异常慢路径</b>，只处理
     * "Redis 重启 / flush 导致 key 在两步之间消失"这种运维级情况。
     */
    private void warmCountIfAbsent(Long videoId) {
        String key = LIKE_COUNT_KEY + videoId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        rebuildCount(videoId);
    }

    /**
     * 给计数加减 delta（原子）。
     *
     * <p><b>实现要点（2026-09-14 改造）</b>：
     * <ol>
     *   <li><b>原子</b>：用 Lua 把"判断 key 是否存在 + INCRBY"合并成一次操作，
     *       不再有 {@code hasKey} 与 {@code increment} 之间的竞态窗口；</li>
     *   <li><b>不再续 TTL</b>：删掉了原来的 {@code expire(key, COUNT_TTL)} —— 那正是
     *       "热 key 永不过期、算错不自愈"的根因；</li>
     *   <li><b>缺失时用数据库真值兜底</b>：本方法是在<b>数据库已经改完之后</b>被调用的，
     *       所以此刻数据库的计数<b>就是</b>正确的当前值 → 直接回填，
     *       <b>绝不能再叠加 delta</b>（否则就是上面那个重复计数 bug）。</li>
     * </ol>
     */
    private void applyDelta(Long videoId, long delta) {
        String key = LIKE_COUNT_KEY + videoId;

        Long result = stringRedisTemplate.execute(
                INCR_IF_EXISTS_SCRIPT, List.of(key), String.valueOf(delta));

        if (result == null || result < 0) {
            // 走到这里说明 key 在"捂热"之后消失了（Redis 重启/flush 等运维级异常）。
            // 注意：这是"慢路径"兜底，重建的是改库之后的真值，不叠加 delta。
            log.warn("点赞计数 key 缺失，按键数据库真值重建：videoId={}, delta={}", videoId, delta);
            rebuildCount(videoId);
        }
    }

    /** 视频必须存在，避免用户给一个不存在的 ID 点赞制造脏数据 */
    private void requireVideoExists(Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new BusinessException("视频ID不合法");
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException("视频不存在");
        }
    }
}
