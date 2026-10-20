# 🎬 直播模块说明（live-backend）

> 用处：README + 面试讲解提纲。直播模块包含——**直播间列表、开播/关播、直播推荐**，
> 以及配套的**实时互动（在线人数 / 礼物榜）**与**弹幕（WebSocket）**。
> 与视频模块(`README_RECOMMEND.md`)是同一套推荐框架，只是对象从 Video 换成 LiveRoom。

## 一、直播模块开了哪些接口

| 接口 | 方法 | 作用 | 需登录 | 已实测 |
|---|---|---|---|---|
| `/api/live/rooms` | GET | 直播间列表（分页/排序/只显示直播中/分区过滤） | ❌ 游客可读 | ✅ |
| `/api/live/rooms` | POST | 创建直播间（首次开播，身份取 Token；**body 只收 title/category/tags/cover，id 由服务端生成**） | ✅ | ✅ |
| `/api/live/rooms/{id}/start` | POST | 开播（仅房主） | ✅ | ✅ |
| `/api/live/rooms/{id}/stop` | POST | 关播（仅房主） | ✅ | ✅ |
| `/api/live/recommend` | GET | 直播推荐（个性化/冷启动） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/online` | GET | 当前在线人数（ZSet `ZCARD`，先剔除心跳超时成员） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/heartbeat` | POST | 在线心跳（每 20s 一次；**取代"靠 leave 减一"的不可靠做法**） | ✅ | ✅ |
| `/api/live/{roomId}/like` | GET | 当前人气（`GET live:like:{roomId}`，缺失回退 DB） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/like` | POST | 点赞/人气 `+delta`（`INCRBY`；可连点、每秒限速 20） | ✅ | ✅ |
| `/api/live/{roomId}/gift/rank` | GET | 礼物榜 TopN（`ZREVRANGE WITHSCORES`，`top` 夹在 1~100） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/enter`、`/leave` | POST | 进房 / 离房（在线人数 ZSet `ZADD` / `ZREM`） | ✅ | ✅ |
| `/api/live/{roomId}/gift` | POST | 送礼物（收礼者由房间推导，`value` 必须是有限正数且 ≤ 100 万） | ✅ | ✅ |
| `/ws/danmaku/{roomId}` | WS | 实时弹幕（握手用**一次性票据** `?ticket=`；为兼容脚本仍支持 `?token=`） | ✅ | ✅ |
| `/api/danmaku/ws-ticket` | POST | 换取 WS 握手票据（60 秒、只能用一次） | ✅ | ✅ |
| `/api/danmaku` | GET / POST | 历史弹幕（分页）/ 服务端代发（昵称由服务端查库，不采信前端） | GET 游客可读 | ✅ |

> 游客可读白名单定义在 `JwtInterceptor#GUEST_READABLE_PATHS` / `#GUEST_READABLE_PATTERN`：
> `GET /api/live/rooms`、`GET /api/live/recommend`、`GET /api/danmaku`，
> 以及正则 `^/api/live/[^/]+/(online|like|gift/rank)$` —— **只放行 GET，写操作一律要 Token**。
>
> `/api/sensitive/**`（敏感词管理）和 `/api/count/**`（早期计数演示）**也必须登录**：
> 它们原来完全裸奔，`POST /api/sensitive/reload` 一次调用 = 全表读 + 重建整棵 Trie，是个免费的 DoS 入口。
>
> 统一异常返回体仍是 `{code,msg,data}`，但 **HTTP 状态码按语义返回**了（参数校验 400、路径不存在 404、
> 方法不支持 405、唯一键冲突 409、未预期异常 500）—— 原来一律 200，网关/APM 里看不到任何错误率。

## 二、直播间列表（`GET /api/live/rooms`）

- 参数：`pageNum`（第几页）、`pageSize`（每页几条）、`category`（可选分区过滤）
- 返回：`data = {records, total, size, current, pages}`
- 规则：**只查 `is_living=1`（直播中）**，按 **`online_count` 降序**（再按 `like_count`）
- 实现要点：
  - 用 **MyBatis-Plus 分页**（`selectPage` + `IPage`），必须配分页插件（`MybatisPlusConfig`）才会真正 LIMIT
  - 配了索引 `idx_living_online(is_living, online_count)`、`idx_living_category_online(is_living, category, online_count)`
  - ⚠️ **`online_count` 以前是死字段**（开播写 0，之后没人再写），"按在线人数排序"其实一直在按常量排。
    现在由 `OnlineCountSyncTask` 每 30 秒把 Redis 的实时人数刷回这一列
    （写放大从"每进房一次 UPDATE"降到"每 30 秒每房间一次"），排序才真正生效。
  - **面试点**：为什么用分页插件而不是手写 LIMIT；为什么看直播只推直播中的；为什么实时数只放 Redis、DB 只做低频快照

## 三、开播 / 关播（生产端）

| 接口 | 说明 |
|---|---|
| `POST /api/live/rooms` | 创建直播间，创建即直播中，返回 id |
| `POST /api/live/rooms/{id}/start` | 开播 |
| `POST /api/live/rooms/{id}/stop` | 关播 |

> ⚠️ **身份不要再用 query 传 `anchorId`**：`create` / `start` / `stop` 的操作者身份统一由
> `JwtInterceptor` 从 `Authorization: Bearer <token>` 解析后放进 request，Controller 用
> `@RequestAttribute("userId")` 取（见 `LiveRoomController`）。前端传什么都不影响判定。

### 核心业务规则：一个主播同时只能开一个播

`create` / `start` 里都会先调用 `closeOtherLiving(anchorId)`：
把该主播所有 `is_living=1` 的直播间批量置为 `0`，再设置新的为 `1`。这样保证同时只有一个直播。

> ⚠️ **"被强制下播"也要结算人气（2026-10-17 修复）**：`closeOtherLiving` 只改 `is_living`，
> 原来的实现**没有调用 `flushOnStop`**，于是主播开第二场时，上一场的人气就丢在 Redis 里，
> `like_count` 永远停在开播时的 0（只有手动点"关播"才结算）。现在 `closeOtherLiving` 返回
> 被关掉的 id 列表，事务提交后逐个补结算。

### 3 个关键设计（面试问就讲这个）

1. **为什么用事务？** 「关旧的 + 开新的」是两个写操作，要么都成功要么都失败，避免"新没开成、旧被关掉"的中间态。
   实现上不是 `@Transactional`，而是 **`TransactionTemplate`**：事务里只放 SQL，
   结算人气（读 Redis + 回写 DB + 删 key）放在**提交之后** —— 这样事务回滚就不会白删 Redis 计数。
2. **为什么不用数据库唯一索引硬卡？** 因为 `is_living` 有 0/1 两值，"下播后再开播"会产生多条 `(anchor_id, 0)`，唯一索引拦不住；用代码兜底更合理。
3. **权限校验 + id 不可注入**：`start/stop` 里 `if (!room.getAnchorId().equals(userId))` 拒绝非房主；
   创建接口的入参是 `CreateLiveRoomRequest`（只有 title/category/tags/cover），
   直播间 id 由服务端生成（`毫秒×1000+序号`，控制在 JS 安全整数内）。
   ✅ 已实现 —— 原来用实体接参，客户端能指定 `id`，而 `create` 在 insert 之前就调了
   `clearCount(id)`（删 Redis 人气 key，且 Redis 不参与事务回滚），等于**任何登录用户都能把任意直播间人气清零**。

## 四、直播推荐（`GET /api/live/recommend`）

和视频推荐同构（`LiveRoomRecommendService`，不是 `RecommendService`），对象换成 `LiveRoom`，唯一差异是**只推直播中**。

- 冷启动：无行为 → 全站在线人数最多的直播间（`is_living=1`）
- 个性化：从 `live_user_watch` 反查用户常看分区 → 推该分区直播中的直播间，排除已看的
- **排序键分两段（和视频推荐一样，容易记混）**：
  - 分区召回 `LiveRoomMapper.selectByCategoryExcludeAndOrderByHot` → `ORDER BY online_count DESC`（只按在线人数）
  - 冷启动/兜底 `LiveRoomMapper.selectHotExclude` → `ORDER BY (online_count*3 + like_count) DESC`（即 `LiveRoom.liveScore()`，在线权重最高）
- **两个修正（2026-10-17）**：
  - **结果会重复**：兜底补齐只排除了 `watchedRoomIds`，没排除本轮已召回的 `result`，
    同一个直播间会在推荐流里出现两次 —— 现在 `exclude = 看过的 ∪ 本轮已召回`；
  - **`NOT IN` 列表无上限**：排除靠 `id NOT IN (...)`，重度用户能带几万个参数（`max_allowed_packet` / 解析成本），
    现在取最近 200 条（走 `idx_user_time`）。
- ⚠️ **`userId` 目前仍由前端 query 传参**（`LiveRoomRecommendController` 注释里标了待改）：
  推荐接口在 `WebConfig` 白名单里、不走 JWT 拦截器，所以拿不到 Token 身份。
  与点赞/开播/弹幕"身份一律取 Token"的做法**不一致**，属于已知待办。

## 五、依赖的表

| 表 | 作用 |
|---|---|
| `live_room` | 直播间（title/cover/anchor_id/category/tags/online_count/like_count/is_living） |
| `live_user_watch` | 用户观看记录（个性化推荐的行为数据） |
| `danmaku` | 弹幕（room_id/user_id/nickname/content/color/create_time，索引 `idx_room_time(room_id,id)`） |
| `sensitive_word` | DFA 敏感词库（`word` 唯一，服务启动时全量加载进 Trie） |

建表脚本：`src/main/resources/sql/live_recommend_init.sql`、`danmaku_init.sql`、`sensitive_word_init.sql`
索引脚本：`src/main/resources/sql/live_room_index.sql`

## 六、业务边界（面试必问）

**开播/关播接口只管理"直播间记录"，不碰摄像头/推流。**
- 视频采集、编码、推流由**前端**（`getUserMedia` + OBS/SRS 流媒体服务器）负责；
- **后端(Java)**只做直播间的信息管理 + 弹幕转发 + 在线人数。
- 所以简历/面试说清楚：摄像头采集在前端，后端只管"直播间状态 + 实时文字互动"。

## 七、怎么跑起来

```bash
# 建表 + 演示数据
mysql -uroot -p123456 < src/main/resources/sql/live_recommend_init.sql
# 建索引（可选，优化分页排序）
mysql -uroot -p123456 < src/main/resources/sql/live_room_index.sql

# ⚠️ 本地演示必须带 dev profile：默认（生产）profile 下 mock 短信是关闭的，发码接口会直接失败
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 列表（分页）
curl "http://localhost:8080/api/live/rooms?pageNum=1&pageSize=10"
# 先登录拿 Token（登录/注册是白名单，不需要 Token）
TOKEN=$(curl -s -X POST "http://localhost:8080/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 创建（身份来自 Token；body 只有 title/category/tags/cover，传 id / anchorId / likeCount 都会被忽略）
curl -X POST "http://localhost:8080/api/live/rooms" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"我的直播","category":"游戏"}'
# 开播/关播（id 用创建返回的）
curl -X POST "http://localhost:8080/api/live/rooms/{id}/start" -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:8080/api/live/rooms/{id}/stop"  -H "Authorization: Bearer $TOKEN"
# 进房 + 心跳（心跳每 20s 一次，60s 没心跳会被自动剔出在线集合）
curl -X POST "http://localhost:8080/api/live/{id}/enter"     -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:8080/api/live/{id}/heartbeat" -H "Authorization: Bearer $TOKEN"
# 直播推荐
curl "http://localhost:8080/api/live/recommend?userId=1&size=8"
```

### WebSocket 弹幕怎么连（票据，不要再把 JWT 拼 URL 上）

```bash
# ① 用普通 HTTP 换一张 60 秒、一次性的握手票据（这一步可以带 Authorization 头）
curl -X POST "http://localhost:8080/api/danmaku/ws-ticket" -H "Authorization: Bearer $TOKEN"
# → {"code":0,"data":{"ticket":"8f3c...","expireSeconds":60}}

# ② 拿票据连（前端 room.js 就是这么做的）
#    ws://localhost:8080/ws/danmaku/{roomId}?ticket=8f3c...
#    兼容：老脚本仍可用 ?token=<JWT>
```

> 为什么不再把 JWT 拼在 URL 上：URL 会进 Tomcat/nginx 的 access log、代理日志、Referer，
> 等于把"有效期 2 小时的登录凭证"撒到日志里。票据 60 秒过期且**只能用一次**（Lua `GET+DEL` 原子消费），
> 泄漏了也只是一张废纸。

## 八、当前进度（2026-09 实测通过，2026-10-17 补齐一轮修复）

> ⚠️ **本节已按最新代码修正**。下方原列的"第 2/3 周待做"项**均已完成**：
> WebSocket 弹幕、Redis 在线人数、JWT 鉴权（`anchorId` 已改为**从 Token 解析**）现已全部落地。

- [x] 直播间列表（分页/降序/只显示直播中）
- [x] 创建直播间（入参 DTO，**id 由服务端生成**，不再接受客户端指定）
- [x] 开播 / 关播 / 权限拦截（非房主拒绝）
- [x] 一个主播同时只开一个播（**被强制下播的房间会补结算人气**）
- [x] 直播推荐（个性化 + 冷启动；**结果去重 + 排除列表封顶 200**）
- [x] **WebSocket 弹幕（实时 + 历史分页 + DFA 敏感词拦截）**
- [x] **弹幕会话容器优化（2026-10-09）**：内层容器 `CopyOnWriteArraySet` → `ConcurrentHashMap<sessionId, WebSocketSession>`
      （写操作从"每次复制整个数组"的 **O(n)** 降到 **O(1)**）；
      建连/断连统一走 `compute(roomId, ...)`，把"建容器 + 增删会话 + 空了删 key"放进同一临界区（消除僵尸会话）；
      广播遍历 `values()` 并利用 **弱一致迭代器** 顺手清理已关闭/发送失败的会话；
      **清理机制三入口**：`afterConnectionClosed`、`handleTransportError`、广播时的死连接清理 —— 三者共用幂等的 `removeSession(roomId, session)`
- [x] **弹幕通道的三个补强（2026-10-17）**：
      ① **昵称不采信前端**（握手时按 userId 查库，REST 通道同理）—— 原来任何人都能在弹幕里冒充主播；
      ② **内容长度 / 颜色 / 频率校验**（content ≤ 200、color 只认 `#rgb|#rrggbb`、单连接 5 条/秒）；
         原来超长内容会被数据库 `varchar(255)` 拒绝，用户只看到"发送失败，请稍后再试"；
      ③ **连接级 Token 过期检查**（握手只校验一次，长连接必须自己判断过期，否则过期几小时还能发弹幕）
- [x] **WebSocket 握手改一次性票据**（`POST /api/danmaku/ws-ticket`，60s TTL + Lua 原子消费）
- [x] **Redis 在线人数（ZSet + 心跳）+ 礼物排行榜（ZSet）**
      —— 在线集合 `member=userId, score=最后心跳时间`，读人数时先用 Lua `ZREMRANGEBYSCORE` 剔除超时成员再 `ZCARD`；
      彻底解决"靠前端 `beforeunload` 调 leave 减一 → 客户端崩溃/断网时人数只增不减"的幽灵在线问题
- [x] **在线人数回写**：`OnlineCountSyncTask` 每 30 秒把 Redis 人数刷回 `live_room.online_count`
- [x] **直播间点赞 / 人气**（`INCRBY live:like:{roomId}`：不判重不取消、可连点；Lua 限速 20 次/秒；
      开播归零、关播结算写回 `live_room.like_count`；TTL 24h 兜底）✅ 2026-10-07
- [x] **礼物榜边界修复**：`ZREVRANGE` 改 `reverseRangeWithScores`（原来 n+1 次往返）；
      `top=0` 原来会 `0..-1` **返回整张榜**，现在夹在 1~100；`value` 拒绝 NaN/Infinity/超大值
- [x] **JWT 鉴权**（开播/关播/发弹幕从 Token 取身份；游客可读列表）
- [x] **JWT 解析只做一次**：`JwtUtil.parseTokenInfo()` 一次解析拿齐 userId/username/剩余时间/loginAt
      （原来拦截器每请求调 4 个方法 → 验签 4 次）
- [x] 直播推荐 + 视频推荐 + 视频点赞（详见 `README_RECOMMEND.md`）

**仍未做**：
- [ ] `GET /api/live/rooms/{id}`（单房间详情）
- [ ] 推荐接口的 `userId` 仍由前端 query 传（应该从 Token 取）
- [ ] Swagger / Docker 部署
- [ ] ~~AI 审核~~ → **已移出本项目**，归入项目二（智能体项目，Spring AI + DeepSeek）
