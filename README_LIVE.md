# 🎬 直播模块说明（live-backend）

> 用处：README + 面试讲解提纲。直播模块包含——**直播间列表、开播/关播、直播推荐**，
> 以及配套的**实时互动（在线人数 / 礼物榜）**与**弹幕（WebSocket）**。
> 与视频模块(`README_RECOMMEND.md`)是同一套推荐框架，只是对象从 Video 换成 LiveRoom。

## 一、直播模块开了哪些接口

| 接口 | 方法 | 作用 | 需登录 | 已实测 |
|---|---|---|---|---|
| `/api/live/rooms` | GET | 直播间列表（分页/排序/只显示直播中/分区过滤） | ❌ 游客可读 | ✅ |
| `/api/live/rooms` | POST | 创建直播间（首次开播，身份取 Token） | ✅ | ✅ |
| `/api/live/rooms/{id}/start` | POST | 开播（仅房主） | ✅ | ✅ |
| `/api/live/rooms/{id}/stop` | POST | 关播（仅房主） | ✅ | ✅ |
| `/api/live/recommend` | GET | 直播推荐（个性化/冷启动） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/online` | GET | 当前在线人数（`SCARD`） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/like` | GET | 当前人气（`GET live:like:{roomId}`，缺失回退 DB） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/like` | POST | 点赞/人气 `+delta`（`INCRBY`；可连点、每秒限速 20） | ✅ | ✅ |
| `/api/live/{roomId}/gift/rank` | GET | 礼物榜 TopN（`ZREVRANGE`） | ❌ 游客可读 | ✅ |
| `/api/live/{roomId}/enter`、`/leave` | POST | 进房 / 离房（在线人数 ±1） | ✅ | ✅ |
| `/api/live/{roomId}/gift` | POST | 送礼物（`receiverId`、`value`） | ✅ | ✅ |
| `/ws/danmaku/{roomId}` | WS | 实时弹幕（握手带 `?token=`） | ✅ | ✅ |
| `/api/danmaku` | GET / POST | 历史弹幕（分页）/ 服务端代发 | GET 游客可读 | ✅ |

> 游客可读白名单定义在 `JwtInterceptor#GUEST_READABLE_PATHS` / `#GUEST_READABLE_PATTERN`：
> `GET /api/live/rooms`、`GET /api/live/recommend`、`GET /api/danmaku`，
> 以及正则 `^/api/live/[^/]+/(online|like|gift/rank)$` —— **只放行 GET，写操作一律要 Token**。

## 二、直播间列表（`GET /api/live/rooms`）

- 参数：`pageNum`（第几页）、`pageSize`（每页几条）、`category`（可选分区过滤）
- 返回：`data = {records, total, size, current, pages}`
- 规则：**只查 `is_living=1`（直播中）**，按 **`online_count` 降序**（再按 `like_count`）
- 实现要点：
  - 用 **MyBatis-Plus 分页**（`selectPage` + `IPage`），必须配分页插件（`MybatisPlusConfig`）才会真正 LIMIT
  - 配了索引 `idx_living_online(is_living, online_count)`、`idx_living_category_online(is_living, category, online_count)`
  - **面试点**：为什么用分页插件而不是手写 LIMIT；为什么看直播只推直播中的

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

### 3 个关键设计（面试问就讲这个）

1. **为什么用 `@Transactional`？** 「关旧的 + 开新的」是两个写操作，要么都成功要么都失败，避免"新没开成、旧被关掉"的中间态。
2. **为什么不用数据库唯一索引硬卡？** 因为 `is_living` 有 0/1 两值，"下播后再开播"会产生多条 `(anchor_id, 0)`，唯一索引拦不住；用代码兜底更合理。
3. **权限校验**：`start/stop` 里 `if (!room.getAnchorId().equals(userId))` 拒绝非房主操作；
   `userId` 取自 JWT（`@RequestAttribute("userId")`），**不从 query 传参** —— 否则任何人都能冒充别人开播/关播。✅ 已实现。

## 四、直播推荐（`GET /api/live/recommend`）

和视频推荐同构（`LiveRoomRecommendService`，不是 `RecommendService`），对象换成 `LiveRoom`，唯一差异是**只推直播中**。

- 冷启动：无行为 → 全站在线人数最多的直播间（`is_living=1`）
- 个性化：从 `live_user_watch` 反查用户常看分区 → 推该分区直播中的直播间，排除已看的
- **排序键分两段（和视频推荐一样，容易记混）**：
  - 分区召回 `LiveRoomMapper.selectByCategoryExcludeAndOrderByHot` → `ORDER BY online_count DESC`（只按在线人数）
  - 冷启动/兜底 `LiveRoomMapper.selectHotExclude` → `ORDER BY (online_count*3 + like_count) DESC`（即 `LiveRoom.liveScore()`，在线权重最高）
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

mvn spring-boot:run

# 列表（分页）
curl "http://localhost:8080/api/live/rooms?pageNum=1&pageSize=10"
# 先登录拿 Token（登录/注册是白名单，不需要 Token）
TOKEN=$(curl -s -X POST "http://localhost:8080/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 创建（身份来自 Token，body 里不用也不该传 anchorId）
curl -X POST "http://localhost:8080/api/live/rooms" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"我的直播","category":"游戏"}'
# 开播/关播（id 用创建返回的）
curl -X POST "http://localhost:8080/api/live/rooms/{id}/start" -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:8080/api/live/rooms/{id}/stop"  -H "Authorization: Bearer $TOKEN"
# 直播推荐
curl "http://localhost:8080/api/live/recommend?userId=1&size=8"
```

## 八、当前进度（2026-09 实测通过）

> ⚠️ **本节已按最新代码修正（2026-09-13）**。下方原列的"第 2/3 周待做"项**均已完成**：
> WebSocket 弹幕、Redis 在线人数、JWT 鉴权（`anchorId` 已改为**从 Token 解析**）现已全部落地。

- [x] 直播间列表（分页/降序/只显示直播中）
- [x] 创建直播间
- [x] 开播 / 关播
- [x] 权限拦截（非房主拒绝）
- [x] 一个主播同时只开一个播
- [x] 直播推荐（个性化 + 冷启动）
- [x] **WebSocket 弹幕（实时 + 历史分页 + DFA 敏感词拦截）**
- [x] **Redis 在线人数（Set）+ 礼物排行榜（ZSet）**
- [x] **直播间点赞 / 人气**（`INCRBY live:like:{roomId}`：不判重不取消、可连点；Lua 限速 20 次/秒；
      开播归零、关播结算写回 `live_room.like_count`；TTL 24h 兜底）✅ 2026-10-07
- [x] **JWT 鉴权**（开播/关播/发弹幕从 Token 取身份；游客可读列表）
- [x] 直播推荐 + 视频推荐 + 视频点赞（详见 `README_RECOMMEND.md`）

**仍未做**：
- [ ] `GET /api/live/rooms/{id}`（单房间详情）
- [ ] Swagger / Docker 部署
- [ ] ~~AI 审核~~ → **已移出本项目**，归入项目二（智能体项目，Spring AI + DeepSeek）
