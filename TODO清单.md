# 📋 live-backend 开发 TODO 清单

> 记录当前后端项目所有待办（功能开发 + 优化 + 技术债），按优先级排。
> **最后校正：2026-10-17** —— 已把"第 2/3 周已完成"的项从待办中摘除（旧版把它们全列成未打勾，会误判进度）；
> 2026-10-07 另做了一次数据库体检，修复项见下方「🩺 2026-10-07 数据库体检」；
> **2026-10-17 做了一轮 P0/P1/P2 bug 修复（见「🔧 2026-10-17 修复轮」）**。
> 总览版见 `docs/项目/TODO清单.md`；本轮实施记录见 `docs/计划/2026-09-13.md`。

---

## ✅ 已完成（不再列入待办，仅存档）

- [x] WebSocket 弹幕实时收发（分房间隔离 + 广播 + 异常回 SYSTEM 提示）
- [x] **弹幕会话容器重构（2026-10-09）** —— 内层 `CopyOnWriteArraySet` → `ConcurrentHashMap<sessionId, WebSocketSession>`
      （`add/remove` 由 **O(n) 复制整个数组** 降为 **O(1)**）；建连/断连统一 `compute` 保证"建容器 + 增删 + 空了删 key"原子；
      广播遍历 `values()` 用弱一致迭代器**边遍历边清理死连接**；**新增 `handleTransportError`**（日志 + `removeSession` + `close(SERVER_ERROR)`）兜底；
      三处清理共用幂等 `removeSession(roomId, session)`
- [x] 弹幕表 `danmaku` + 历史查询（分页 `/api/danmaku?roomId=`）+ 服务端代发
- [x] JWT 登录鉴权（`/create`、`/start`、`/stop`、发弹幕均从 Token 取身份）+ 滑动续期
- [x] 游客可读白名单（只读接口按"读/写"授权，不再按路径前缀一刀切）
- [x] 参数校验 + 统一异常处理（`@Valid` + `GlobalExceptionHandler`）
- [x] DFA 敏感词过滤（Trie + DB 词库加载 + 热更新）
- [x] Redis 在线人数（**ZSet + 心跳**：`ZADD` / `ZREM` / `ZREMRANGEBYSCORE` 剔除 + `ZCARD`）+ `GET /api/live/{id}/online`
      + `POST /api/live/{id}/heartbeat`（2026-10-17 从 Set 改成 ZSet，解决"客户端崩溃就只增不减"）
- [x] 在线人数**回写** `live_room.online_count`（`OnlineCountSyncTask`，每 30 秒一次）
- [x] Redis 礼物榜（ZSet `ZINCRBY/ZREVRANGE`）
- [x] 视频点赞（权威 `user_like` + 派生 Redis + 定时对账；幂等 + 行数门控）
- [x] 密码 BCrypt 加密（老明文数据登录时懒迁移）
- [x] 前端 6 页（B 站风格，可现场演示）

---

## 🔴 下一步优先（2026-09-14 的 P0/P1）

### P0 · 直播间点赞（人气）✅ **已完成（2026-10-07）**
- [x] `POST /api/live/{roomId}/like`（body `{"delta":N}`）→ `INCRBY live:like:{roomId}` ✅
  - **不做判重、不做取消**（语义 = 本场人气）✅；前端本地聚合 + 每 500ms 批量上报 ✅（`room.js` 的 `flushLike`）
  - 开播归零、**关播写回 + `DEL` key** ✅（`LiveLikeService#clearCount` / `#flushOnStop`，接在 `LiveRoomService` 的 start/stop 上）
  - 频率限制（同用户每秒 ≤20 次）✅ —— 和计数放在**同一个 Lua 脚本**里，避免"限速过了、计数没加"的中间态
- [x] 配套加了 `GET /api/live/{roomId}/like`（游客可读）：Redis 优先，key 不存在回退数据库结算值
- 实现：`LiveLikeService`（Lua 限速+累加、TTL 24h 兜底）+ `LiveInteractiveController#like/#likeCount`
- **实测**：`{delta:5}` → 人气 5；同一秒连打 25 次 → 恰好 20 成功 / 5 被限速；
  关播后 Redis key 删除、数据库写回 25；重新开播归零
- 📌 面试素材：**直播间点赞和视频点赞是同一个词、完全相反的两套方案**（判重 vs 不判重、
  权威表 vs 纯计数器、对账 vs 关播结算、key 到底该不该设 TTL）—— 详见 `LiveLikeService` 类注释的对比表

### P0 · 点赞高并发三件套（✅ 全部完成，2026-09-14）
- [x] **计数缓存去掉 TTL + 增减原子化** ✅ **已完成（2026-09-14）**
  - `VideoLikeService` 删掉 `COUNT_TTL = 24h`；`rebuildCount()` 改成不带过期时间的 `set`
  - `applyDelta()` 改用 Lua（`INCR_IF_EXISTS_SCRIPT`）：`EXISTS ? INCRBY : 返回 -1`，
    把原来的 `hasKey` + `increment` 两步合成一次原子操作；key 缺失（只可能是 Redis 重启/flush）
    时用**改库之后的数据库真值**回填、**不叠加 delta**（避免退化成重复计数）
  - 配套：Redis 服务端需配 `maxmemory-policy allkeys-lru`（不能是 `volatile-lru`），已写进 `application.yml` 注释
- [x] **Redis 操作移出 `@Transactional`** ✅ **已完成（2026-09-14）**
  - `like`/`unlike` 摘掉了 `@Transactional`，改用 `TransactionTemplate` **只把"写权威表"那一条 SQL 包在事务里**
  - Redis（`warmCountIfAbsent` / `applyDelta` / `getLikeCount`）与 MQ 投递全部在**事务提交之后**：
    DB 回滚就不会多加一个赞；Redis 抖动也不再占着数据库连接
  - 用 `TransactionTemplate` 而不是拆类，是为了绕开"同类自调用 `@Transactional` 失效"的坑
- [x] **Hikari 显式配置** ✅ **已完成（2026-09-14）** —— `application.yml` 加 `spring.datasource.hikari`：
      `maximum-pool-size: 20`、`minimum-idle: 10`、`connection-timeout: 3000`、`max-lifetime: 30min`、
      `leak-detection-threshold: 10000`（专抓"事务里干慢活"，就是这一类的回归防线）

### P1 · 对账改造与索引
- [x] **对账任务改"脏集合"对账** ✅ **已完成（2026-10-07）** —— 原来是 `user_like` 全表 `GROUP BY`
      （`VideoMapper.reconcileLikeCount`）每 5 分钟扫一次全表，上量后是灾难。
      现在改成**两级对账**：
      - **① 脏集合（主力，30 秒一轮）**：点赞/取消点赞时 `SADD like:dirty:video {videoId}`，
        对账**非破坏性取一批**（`distinctRandomMembers`，`dirty-batch-size` 默认 1000）做**定点修正** ——
        只查这批 videoId 的真实赞数（`WHERE video_id IN (...)` 走 `idx_video` 范围扫描），
        用 Java 比对后只更新对不上的行；**处理成功才 `SREM`**（失败留在集合里下轮重试）
      - 2026-10-17 修正：原来用 `SPOP`（取出即删除），标记在数据库操作**之前**就没了 ——
        这一批随后失败/进程重启就**永久丢失**，只能等凌晨全量兜底；另外混入非法成员会让
        `Long.valueOf` 抛异常、任务每 30 秒崩一次，现在过滤掉
      - 2026-10-17 补：修正后**同时同步 Redis 计数**（原来只修 `video.like_count`，
        而列表展示读的是 Redis，且该 key 不设 TTL → 偏差永不自愈）
      - **② 全量对账（兜底，每天凌晨 3 点）**：脏集合在 Redis 里，重启/flush 就丢了，
        那些"脏了但标记丢了"的视频只有全量对账能兜住 —— 两级是互补，不是替代
      - 实现：`service/LikeReconcileService`（`markDirty` / `reconcileDirty` / `reconcileAll`）
        + `task/LikeCountReconcileTask`（两个 `@Scheduled`）+ `VideoMapper` 两个新方法
      - **实测**：把 `like_count` 人为改成 999 → 取消点赞标脏 → 30 秒内自动修正为真实值（`Updates:1`）；
        塞 1200 个脏 id → 一轮只处理 1000、剩 200 下轮继续（**限批生效**）
- [x] **两个索引 DDL** ✅ **已完成（2026-10-07）** —— 脚本：`src/main/resources/sql/video_index.sql`
      `video` 新增 `idx_heat`（热度表达式函数索引）+ `idx_category_play(category, play_count DESC)`。
      30 万行合成表实测：热度排序 **189ms → 0.24ms**（≈780×，走 `Index scan using idx_heat (reverse)`）；
      分区列表 **160ms → 1.6ms**（≈100×，真实表上 `Using filesort` 已消失）
  > ⚠️ 热度表达式现在有**三处**必须同步：`VideoMapper` 的 SQL、`Video.heatScore()`、`idx_heat` 的索引定义

### P2 · 接口补齐
- [ ] **`GET /api/live/rooms/{id}`**（单房间详情）—— 现在前端只能从列表里"找"房间
- [x] **`LiveRoomVO` 暴露 `anchorId`** ✅ **已完成（2026-10-07）** —— 现在送礼不用用户手填主播 id（详见下方「🔐 越权修复」）

### P3 · 视频分区与列表优化
- [ ] `GET /api/video?category=&pageNum=&pageSize=` + `VideoController`（视频页分区）
- [ ] `likedVideoIds` 加上限（重度用户几万赞会撑爆内存）+ `NOT IN` 改写（`LEFT JOIN ... IS NULL`）

---

## 🟡 其他优化 / 工程化

- [ ] **`PhoneLoginService` 里同样的"Redis 在事务内"问题（9-14 排查新发现）**
  - `login()` / `bind()` 都挂了 `@Transactional`，且都在**数据库访问之后**才调 `smsCodeService.clear(phone)`（Redis `DEL`）
    → 事务开着时连接被借走，Redis 的这次 DEL 期间连接就是白占的（虽然只是一个 DEL，比点赞那处轻）
  - `smsCodeService.verify()`（Redis GET）在方法开头、DB 访问之前，此时连接还没被借走，**问题不大**
  - 建议改法与点赞一致：用 `TransactionTemplate` 只包"查/建用户 / updateById"，
    `verify`（校验）放事务前、`clear`（用后即焚）放**事务提交后** —— 顺带修掉一个隐患：
      现在 `clear` 在事务内，Redis 操作**不参与回滚**，若 DB 之后失败，验证码已经被烧掉了
- [ ] 去掉 `requireVideoExists` 的 `selectById`（改本地缓存）
- [ ] 直播间点赞上报里的"房间是否存在"校验也是每次一次 PK 查询 —— 前端已攒批（500ms 一次）量级可接受，
      再往上可以本地缓存，和上一条同源
- [ ] 预检改**乐观插入**（直接 `INSERT` + catch `DuplicateKeyException`，省一次 SELECT）
- [ ] 接入 **RabbitMQ**，用 `LikeCountPublisher` 攒批异步刷回 `video.like_count`（接口已留好，换实现即可）
- [ ] 推荐接口升级：「已看集合放 Redis + 推荐结果短缓存」
- [ ] 推荐 `NOT IN` 改 `NOT EXISTS` / `LEFT JOIN ... IS NULL`
- [ ] **废弃 `/api/count/**`**（早期演示接口：无鉴权、无判重，已被正式点赞接口取代）—— ⏳ 待决定
- [ ] 删 `RedisRankService.redisTemplate` 死字段；删无引用的 `css/style.css`
- [ ] 登出黑名单（**设计已完成，未落地**：会话级，复用 `loginAt` 当 session id，见 `docs/计划/2026-09-10.md` 第六节）
- [ ] 验证码限流 / 错误次数限制（当前后端没有，可被刷短信 / 暴力枚举）
- [ ] 前端 401 未清 Token（`js/common.js` 的 `App.api()` 遇 401 只弹 toast，没清 localStorage）
- [ ] `live_room` 主键改雪花（`@TableId(type = IdType.ASSIGN_ID)`）
- [ ] JDBC URL 加 `rewriteBatchedStatements=true`（大批量插入用）
- [x] 分页/排序索引确认（`live_room_index.sql` 是否已执行）✅ **2026-10-07 已确认**：两个索引都在，EXPLAIN 走 `idx_living_online` / `idx_living_category_online`（Backward index scan，无 filesort）
- [ ] **补单元测试** —— 那个"重复计数 Bug"，如果当初写了 `like→like→unlike→unlike` 的断言测试，第一次就能跑出来

---

## 🩺 2026-10-07 数据库体检（MySQL 8.0.43 / db1）

> 一次性体检 + 修复，修复脚本 `src/main/resources/sql/db1_repair_20261007.sql`（可重跑）。
> 执行前已把 `db1`、`video_app`、`comment_bak_20260910` 全量导出到 `.dbbackup/`，可回滚。

### 已修复

- [x] **`video` 加两条索引** —— 见上方 P1，脚本 `src/main/resources/sql/video_index.sql`
- [x] **`video` 表乱码修复** —— `category` 默认值是 `鍏朵粬`（≠ `其他`），另有 4 条列注释是 GBK 误读产生的乱码，
      一并写回正常中文。危害最大的是默认值：INSERT 不传 category 会写进一个谁也匹配不上的分区。
      （只有 `video` 中招；`live_room`/`comment`/`danmaku` 干净，数据行一直是好的）
- [x] **`live_room.is_living` 默认值 1 → 0** —— 避免"INSERT 漏传字段就凭空多出一场直播"
- [x] **修正种子数据** —— anchor 3/5/9/10 各有 2 个 `is_living=1` 的直播间，违反"一主播同时只开一播"
      （该规则只在 `LiveRoomService.closeOtherLiving()` 里兜底，直接 INSERT 的种子数据绕过了它）。
      每个主播保留人气最高的那一场，关闭 4 个 → 现在 **7 个直播中 / 7 个主播**
- [x] **清理遗留对象** —— 删废弃库 `video_app`（7 张空表、全项目零引用）、删 `comment_bak_20260910` 备份表
- [x] **确认 `live_room_index.sql` 已执行且生效**

### 体检通过（无需处理）

- **点赞计数与权威表完全一致**：`user_like` 9 行 = `SUM(video.like_count)` 9，差异行 **0**
- **无孤儿数据**：`user_like` / `live_user_watch` / `danmaku` 与主表关联失配均为 0
- `uk_user_video` 唯一索引在位（幂等兜底）；全部表 InnoDB
- 服务端：`slow_query_log=ON`（阈值 10s）、buffer pool 128MB、`log_bin=ON` +
  `sync_binlog=1` + `innodb_flush_log_at_trx_commit=1` → **提交即刷盘**，
  这是点赞 TPS 的硬上限来源，面试讲"点赞高并发"时必须能解释这一环

### 待决定

- [ ] **Redis 配置与项目自身要求不符**：实测 `maxmemory=0` + `maxmemory-policy=noeviction`，
      而 `application.yml` 注释明确要求 `allkeys-lru`。现在 maxmemory=0 所以不淘汰（不会丢数据），
      但**没有内存上限**；若哪天只设 `maxmemory` 不改策略，点赞的 `INCRBY` 会直接报 OOM 错误
- [ ] **`idx_play_count` 冗余可删** —— 代码里没有"只按 play_count 排序"的查询，保留只多一份写放大
- [ ] `danmaku`/`sensitive_word` 是 `utf8mb4_general_ci`，其余表 `utf8mb4_0900_ai_ci`
      （本项目没有跨表字符串比较，暂无实际影响，统一一下更干净）
- [x] ~~`live_room.online_count` 仍是假数据（接口创建的房间恒为 0），真值在 Redis~~
      → **2026-10-17 已修**：`OnlineCountSyncTask` 每 30 秒把 Redis 人数刷回这一列

---

## 🔐 2026-10-07 越权修复（身份口径统一）

- [x] **`LiveInteractiveController` 的 `enter` / `leave` / `gift` 身份改从 Token 取**
  - 原状：`userId` 取自 **query 参数**、`receiverId` **直接采信前端** —— 任何人填一个别人的 id，
    就能替别人进房/离房（刷在线人数）、把礼物记到任意用户名下；
    而开播/关播/发弹幕早就是"只认 Token"，**同一套系统两种口径**，是明显的越权口子
  - 现在：**操作者一律 `@RequestAttribute("userId")`**；**收礼者由服务端从房间推导**（= `live_room.anchor_id`），
    前端仍可传 `receiverId` 但会校验必须等于房间主播，不传就用推导值
  - 顺带补了：房间不存在 / 礼物价值 ≤ 0 的校验
  - **实测证据**：带 `?userId=999999` 进房后，Redis `online:204` 里是 **Token 用户 1**，不是 999999；
    传错 `receiverId=999` 被拒（"收礼者不是本房间主播"），不传/传对都成功
- [x] **`LiveRoomVO` 暴露 `anchorId`** —— 房间页自动填好收礼者并设为只读，用户不用再手填
- [x] 待同步的文档：`docs/项目/互动功能后端设计方案.md`（`receiverId` 已变可选）、
      `docs/项目/apifox接口测试.md`（`enter`/`leave` 的 `userId` 参数已废弃）
      → **2026-10-17 已同步**（连同在线人数改 ZSet、WS 改票据、敏感词接口需登录等一起更新）

> 一句话原则（面试可讲）：**凡是服务端能推导出来的身份和目标，都不该由前端决定。**

---

## 🔧 已知技术债

- [x] ~~密码暂明文~~ → **已换 BCrypt**
- [ ] **`gift:rank:{roomId}`（礼物榜）没有落库，属于"淘汰即永久丢"的数据**（2026-09-23 排查 Redis 淘汰策略时发现）
  - `RedisRankService.addGift()` **只写 ZSet**，没有礼品流水表；类注释里写着"生产会异步落库到 MySQL"，但没做
  - 风险：Redis 内存吃紧时 `allkeys-lru` 会把该 key 淘汰 → **榜单永久消失、无法重建**
    （对比：`video:like:count:*` 被淘汰无所谓——可从 `user_like` `COUNT(*)` 重算；`online:*` 失真后用户重新进房即恢复）
  - 方向：① 补**礼物流水表 + 异步落库**（与 TODO「接入 RabbitMQ」同一条线），Redis 只做实时榜；② 或把"不可丢"的 key 放**单独实例/库并配 `noeviction`**
  - 详见 `docs/redis/Redis-内存淘汰策略.md` 第六节
- [ ] `live_room.id` 混用两套 id 体系（min=201，max=1788100135390，新建房间是"毫秒×1000+序号"的 16 位），不是连续自增
- [ ] `comment.parent_id` 默认 `0` 不是 `NULL` → 查"顶级评论"必须用 `parent_id = 0`，不能用 `IS NULL`

---

## 🔧 2026-10-17 修复轮（P0/P1/P2，均已改完 + 34 个单测通过）

> 回归脚本：`verify-fixes.js`（`node verify-fixes.js`，后端地址用 `BASE` 覆盖）。
> 详细讲解口径见 `docs/求职/项目讲解清单.md` 的「2.5.4 一轮自查出来的 bug」。

### P0 · 正确性 / 安全

- [x] **创建直播间 id 由客户端决定（ID 注入）** —— 原来 `@RequestBody LiveRoom` 实体接参，
      且 `create` 在 insert **之前**就调 `clearCount(room.getId())`（删 Redis 人气 key，Redis 不参与事务回滚）
      → 任何登录用户都能把**任意直播间当场人气清零**。现在入参改 `CreateLiveRoomRequest`，id 由服务端生成
- [x] **发弹幕的昵称由前端决定** —— WS 直接取消息体里的 `nickname`、REST 只在为空时填"游客"，
      等于任何人都能冒充主播发言且**落库永久留存**。现在握手/代发都按 userId 查库（`UserService#displayName`）
- [x] **WS 通道无长度/颜色/频率校验** —— `danmaku.content` 是 `varchar(255)`、`nickname(50)`、`color(20)`，
      超长直接抛"Data too long"、用户只看到"发送失败，请稍后再试"；且**一条连接可无限刷**（写放大 + 广播放大）。
      现在 content ≤ 200、color 只认 `#rgb|#rrggbb`、单连接 5 条/秒
- [x] **`unlike()` 漏了 `requireVideoExists`** —— 对任意不存在的 videoId 调 DELETE 会走到 `rebuildCount()`，
      无条件 `SET video:like:count:{id}`（**无 TTL**）→ 遍历 id 就能把 Redis 内存写满
- [x] **被强制下播的房间不结算人气** —— `closeOtherLiving` 只改 `is_living`、没调 `flushOnStop`，
      于是"开第二场时上一场被踢下线"的人气丢在 Redis 里，`like_count` 永远停在 0
- [x] **在线人数只增不减（幽灵在线）** —— Set + 靠前端 `beforeunload` 调 leave，
      客户端崩溃/断网/切后台时请求根本发不出去 → `SCARD` 单调上涨。
      现在改 **ZSet(member=userId, score=最后心跳) + 超时剔除 Lua**，前端每 20s 心跳

### P1 · 一致性 / 性能

- [x] **对账只修 DB 不修 Redis**（修了库，用户看到的还是错的；且 key 无 TTL 永不自愈）
- [x] **`SPOP` 丢标记** → 改"取样 → 处理成功 → `SREM`"
- [x] **脏集合里的非法成员会让对账任务每 30s 崩一次** → 过滤 + 清理
- [x] **推荐结果重复**（兜底补齐没排除本轮已召回的）→ `exclude = 已看 ∪ 本轮已召回`
- [x] **`NOT IN` 列表无上限**（重度用户几万个参数）→ `likedVideoIds` 限 500、推荐排除限最近 200
- [x] **每请求 JWT 验签 4 次** → `JwtUtil.parseTokenInfo()` 一次解析
- [x] **礼物榜 N+1 + `top=0` 返回整张榜** → `reverseRangeWithScores` + `top` 夹到 [1,100]

### P2 · 健壮性

- [x] `LiveRoom.liveScore()` 的 `Long` 拆箱 NPE 兜底
- [x] 短信超日配额时不释放发送锁、计数还一直涨 → 回退计数 + 释放锁
- [x] 绑手机号"唯一性预检"挪到消费验证码之前（原来撞"已被绑定"时**两个验证码全被吃掉**）
- [x] `String.format("%06d")` 等 3 处补 `Locale.ROOT`（非拉丁数字 locale 会生成非 ASCII 验证码）
- [x] **`/api/sensitive/**`、`/api/count/**` 无鉴权** → 纳入 JWT 拦截 + reload 3 秒节流
- [x] `check` 归一化（小写+去空白）与 `filter` 不一致 → 统一走 `SensitiveWordService#normalize`
- [x] WS 握手校验 roomId 必须是正整数、query 参数 URL 解码、**连接级 Token 过期检查**
- [x] WS 跨域从写死 `*` 改配置项 + 启动 WARN
- [x] 前端 `ws.onclose` 无重连（断线后弹幕永久失效）→ 指数退避重连 + 心跳
- [x] 生产 profile 关掉 SQL `debug`；`password` / `jwt.secret` 支持环境变量
- [x] 异常统一返回 200 → 按语义返回 400/404/405/409/415/500
- [x] **WS 握手从 `?token=` 改一次性票据**（`POST /api/danmaku/ws-ticket`，60s + Lua 原子消费）
      —— JWT 进 URL 会落到 access log / 代理日志 / Referer
- [x] 删除无人使用的 `RedisConfig`（`RedisTemplate<String,Object>` 只被 `RedisRankService` 一个未使用字段引用）

> 仍待决定：`SmsConfigValidator` 对"mock 关闭且无真实实现"只 WARN 还是硬失败；
> 冲突类错误用 409 还是保持 200；JWT 无状态导致**封禁不能立刻生效**；验证码在 Redis 里是明文；
> 手机号在库里是明文列；短信限流只按手机号（没有 IP 维度）。

---

## 📌 不在本项目范围

- **AI 审核（Spring AI + DeepSeek）已移出本项目**，归入**项目二（智能体项目）**，见 `docs/plan.md` 阶段三。
  本项目内容审核只保留 **DFA 敏感词**这一层。

---

## 关联文档

- `docs/项目/TODO清单.md`（总览：功能 + 优化 + 八股 + 技术债）
- `docs/计划/2026-09-13.md`（本轮实施记录 + 实测数据 + 环境踩坑）
- `docs/项目/互动功能后端设计方案.md`、`docs/项目/Redis设计方案.md`
- `docs/项目/apifox接口测试.md`（接口用例）、`docs/项目/项目讲解清单.md`（面试口述稿）
- `README_LIVE.md` / `README_RECOMMEND.md`（模块说明）
