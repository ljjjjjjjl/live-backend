# 直播互动平台后端（live-backend）

> 一个**可现场演示**的直播 / 视频互动平台后端：JWT 鉴权、WebSocket 弹幕、Redis 在线人数与礼物榜、
> 点赞一致性与索引优化。跑起来无需前端工程 —— 自带 6 个原生 HTML/JS 演示页面。
>
> `Java 21` · `Spring Boot 3.3.5` · `MyBatis-Plus` · `MySQL 8` · `Redis` · `WebSocket` · `JWT`

---

## 目录

- [项目简介](#项目简介)
- [功能清单](#功能清单)
- [技术栈](#技术栈)
- [核心设计](#核心设计)
- [快速开始](#快速开始)
- [接口一览](#接口一览)
- [目录结构](#目录结构)
- [已知边界](#已知边界)
- [相关文档](#相关文档)

## 项目简介

仿 B 站的直播互动平台后端，覆盖 **「登录 → 直播间列表 → 进房 → 发弹幕 → 送礼 → 点赞」** 的完整业务闭环。

| 规模 | 数量 |
|---|---|
| REST 接口 | **30 个** + 1 个 WebSocket 通道 |
| 数据表 | **11 张**（其中收藏 / 转发 / 评论 4 张为设计储备，功能未实现） |
| Java 源文件 | 77 个主代码 + 6 个测试类（34 个测试用例） |
| 演示页面 | 6 个（原生 HTML/CSS/JS，启动即可访问） |

## 功能清单

**用户与鉴权**
- [x] 账号密码注册 / 登录，BCrypt 存储密码（早期明文数据登录时懒迁移）
- [x] 手机号 + 短信验证码登录（Mock 发送；验证码存 Redis、用后即焚、登录即注册）
- [x] JWT 鉴权 + **滑动续期**（响应头换发新 Token + 会话绝对上限）
- [x] 游客可读白名单：按「读 / 写」授权，而不是按路径前缀一刀切
- [x] 身份统一从 Token 解析（`@RequestAttribute("userId")`），不接受前端传 userId

**直播间**
- [x] 列表：分页 / 分区过滤 / 只显示直播中 / 在线人数降序
- [x] 创建、开播、关播；**一个主播同时只能开一个播**
- [x] 直播推荐（个性化召回 + 冷启动兜底）

**实时互动（Redis）**
- [x] 在线人数：ZSet `member=userId / score=最后心跳时间`（`ZADD` / `ZREM` / `ZREMRANGEBYSCORE` + `ZCARD`，**心跳超时自动剔除**）
- [x] 在线人数**回写** `live_room.online_count`（每 30 秒一次，让"按在线人数排序"真正生效）
- [x] 礼物榜：ZSet（`ZINCRBY` / `ZREVRANGE WITHSCORES`）
- [x] 直播间人气：`INCRBY` + **Lua 限速（20 次/秒）**，开播归零、关播结算写回 MySQL
- [x] 弹幕：WebSocket 分房间广播，握手用**一次性票据**换连接，异常回执而非静默失败
- [x] 弹幕**不落库**（本轮改造）：处理链是「DFA 校验 → 构造载荷 → 广播」，不碰数据库；
      历史弹幕接口与「服务端代发」接口随之删除，但 DFA 敏感词校验**保留**
      （见 `docs/plan/danmaku-no-persist.md`）

**视频与点赞**
- [x] 视频推荐（分类召回 + 热度排序 + 排除已看 + 冷启动兜底）
- [x] 视频点赞：权威表 `user_like` 同步写 + Redis 计数派生 + Lua 原子化 + 定时对账
- [x] 直播间点赞：纯计数器语义（不判重、不取消）

**内容审核**
- [x] DFA（Trie）敏感词过滤，词库启动全量加载 + 支持热更新

**工程化**
- [x] 参数校验（Jakarta Validation）+ 全局异常处理（**HTTP 状态码按语义返回**：400/404/405/409/415/500）
- [x] Hikari 连接池显式配置（上限 20 + 10s 泄漏检测）
- [x] 单元测试 34 个（`src/test/java`，覆盖短信验证码原子消费、注册/绑定手机号安全、配置校验）
- [ ] Swagger / Docker（未做）

## 技术栈

| 层次 | 技术 |
|---|---|
| 语言 / 运行时 | Java 21（LTS） |
| 框架 | Spring Boot 3.3.5、MyBatis-Plus 3.5.5 |
| 数据库 | MySQL 8（InnoDB） |
| 缓存 | Redis（String / Hash / Set / ZSet / Lua） |
| 实时通信 | WebSocket（Spring WebSocket + 自定义握手拦截器） |
| 鉴权 | JWT（JJWT 0.11.5）+ 自定义 `HandlerInterceptor` |
| 连接池 | HikariCP |
| 构建 | Maven |

## 核心设计

### 1. 点赞：把数据分成「权威」和「派生」

```
user_like 表（权威，绝不能丢）  ──COUNT──┬──►  video.like_count（派生列，热度排序用）
                                         └──►  Redis video:like:count:{id}（派生缓存，展示用）
```

判据一句话：**能不能重算，决定了能不能异步、能不能缓存**。
所以「谁赞了谁」同步写库（唯一索引 + 影响行数门控做幂等），「有多少个赞」才敢放 Redis 实时累加，
再由定时对账任务以权威表为准校正回来。

### 2. 直播间点赞：同一个词，完全相反的方案

| | 视频点赞 | 直播间点赞（人气） |
|---|---|---|
| 判重 / 取消 | 要 / 要 | **都不要**（连点就是人气） |
| 权威数据 | 关系表 `user_like` | **Redis 计数器本身就是权威** |
| 落库 | 定时对账校正 | 开播归零 + 关播结算写回 |
| 计数 key 的 TTL | **不能设** | **要设**（24h 兜底，最坏从 0 重数） |

限速与累加写在**同一个 Lua 脚本**里原子执行，避免「限速过了但计数没加」的中间态。

### 3. 事务边界：Redis 不待在数据库事务里

点赞接口**不挂 `@Transactional`**，改用 `TransactionTemplate` 把「写权威表」那一条 SQL 包进事务，
Redis 读写与异步投递全部放在**事务提交之后**：
数据库回滚不会多加一个赞，Redis 抖动也不会白占着数据库连接。
（用编程式事务而不是拆 Bean，是为了绕开「同类自调用不走代理、`@Transactional` 失效」的坑。）

### 4. 对账：两级设计（脏集合 + 全量兜底）

`video.like_count` 是派生列，靠对账和权威表 `user_like` 保持一致。全表对账会随数据量线性变差
（`user_like` 上亿后每轮全表聚合，会冲掉 buffer pool），所以改成两级：

```
点赞/取消点赞 ──► SADD like:dirty:video {videoId}      ← 谁改谁标记，Set 天然去重
                        │  每 30 秒
                        ▼
        ① 脏集合对账（主力）取一批(默认 1000) → 只查这批 videoId 的真实赞数 → 只改对不上的行
                        │  顺带把修正后的值同步进 Redis 展示计数
                        ▼
        ② 全量对账（兜底）每天凌晨 3 点跑一遍 —— Redis 丢了脏集合也不会永久漏修
```

四个刻意的设计点：**Set 去重**（同一视频点 1 万次只留一个成员）、
**「非破坏性取样 → 处理成功 → 再 `SREM`」而不是 `SPOP`**（`SPOP` 把"取出"和"删除"合成一步，
标记在数据库操作**之前**就没了：这一批随后失败/进程重启，这些 videoId 就永久丢失，只能等凌晨全量兜底；
现在的写法失败就留在集合里下轮重试）、**限批**（避免对账自己攒成大事务）、
**Redis 操作一律在事务外**。

> 还有两处针对"偏差永不自愈"的修补（2026-10-17）：
> ① 对账**同时修 Redis**计数（原来只修 `video.like_count`，而列表页展示读的是 Redis，
> 且该 key 不设 TTL → 修了库不修缓存，用户看到的还是错的）；
> ② 脏集合里混进非法成员时**过滤掉**，否则 `Long.valueOf` 抛异常会让对账任务每 30 秒崩一次、彻底停摆。

### 5. 索引优化：EXPLAIN 驱动，实测 189ms → 0.24ms

热度排序原本是 `possible_keys = NULL` + `Using filesort`。新增两个索引：

```sql
ALTER TABLE video ADD INDEX idx_heat
  ((play_count + like_count*5 + favorite_count*10 + comment_count*8 + forward_count*6));
ALTER TABLE video ADD INDEX idx_category_play (category, play_count DESC);
```

30 万行对照实测：**热度排序 189ms → 0.24ms**、**分区列表 160ms → 1.6ms**，真实表上 `Using filesort` 消失。
（真实表只有 20 行，优化器仍会选全表扫描 —— 那是正确选择，所以用合成数据验证索引本身的收益。）

## 快速开始

```bash
# 1) 建库建表（依次执行）
mysql -uroot -p < src/main/resources/sql/live_recommend_init.sql   # 直播间 + 观看记录
mysql -uroot -p < src/main/resources/sql/recommend_init.sql        # 视频 / 点赞 / 推荐
mysql -uroot -p < src/main/resources/sql/danmaku_init.sql          # 弹幕
mysql -uroot -p < src/main/resources/sql/sensitive_word_init.sql   # 敏感词库
mysql -uroot -p < src/main/resources/sql/live_room_index.sql       # 直播间列表索引
mysql -uroot -p < src/main/resources/sql/video_index.sql           # 视频热度 / 分区索引
mysql -uroot -p < src/main/resources/sql/db1_repair_20261007.sql   # 数据/元数据修复（可选）

# 2) 改配置：src/main/resources/application.yml（MySQL / Redis 连接；密码和 JWT 密钥都支持环境变量覆盖）
# 3) 启动（⚠️ 本地演示必须带 dev profile，否则 mock 短信是关的、发码接口会直接失败）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 4) 打开演示页
#    http://localhost:8080/         登录 / 注册
#    http://localhost:8080/live.html 直播间列表    room.html?id=204 直播间详情
#    http://localhost:8080/home.html 视频推荐      like.html 点赞并发压测
```

> ⚠️ `application.yml` 里的数据库密码 / JWT 密钥支持 `${DB_PASSWORD}` / `${JWT_SECRET}` 环境变量覆盖，
> 文件里的值只是本地演示默认值。默认（生产）profile 下 SQL 日志级别是 `warn`，想看 SQL 用 dev profile。

## 接口一览

**用户与鉴权**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录（返回 token） |
| POST | `/api/auth/sms/send` | 发送短信验证码（开发环境回显 Mock 码） |
| POST | `/api/auth/sms/login` | 手机号验证码登录（登录即注册） |
| POST | `/api/auth/phone/bind` | 绑定手机号 |

**直播间**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/live/rooms` | 列表（分页 / 分区 / 只显示直播中）· 游客可读 |
| POST | `/api/live/rooms` | 创建直播间 |
| POST | `/api/live/rooms/{id}/start` | 开播 |
| POST | `/api/live/rooms/{id}/stop` | 关播（人气结算写回） |
| GET | `/api/live/recommend` | 直播推荐 · 游客可读 |

**实时互动**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/live/{roomId}/enter` | 进房（ZSet 记一次心跳） |
| POST | `/api/live/{roomId}/heartbeat` | 在线心跳（每 20s 一次，60s 没心跳自动剔除） |
| POST | `/api/live/{roomId}/leave` | 离房（仅"加速收敛"，不靠它保正确） |
| GET | `/api/live/{roomId}/online` | 当前在线人数（先剔除超时成员再计数）· 游客可读 |
| POST | `/api/live/{roomId}/like` | 点赞（body `{"delta":N}`，可连点、限速 20/s） |
| GET | `/api/live/{roomId}/like` | 当前人气 · 游客可读 |
| POST | `/api/live/{roomId}/gift` | 送礼物（收礼者 = 房间主播，服务端推导） |
| GET | `/api/live/{roomId}/gift/rank` | 礼物榜 TopN（`top` 夹在 1~100）· 游客可读 |
| POST | `/api/danmaku/ws-ticket` | 换 WS 握手票据（60s、一次性） |
| WS | `/ws/danmaku/{roomId}?ticket=` | 实时弹幕（兼容 `?token=`） |

**视频推荐与点赞**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/recommend` | 视频推荐 · 游客可读 |
| POST | `/api/video/{videoId}/like` | 点赞（幂等） |
| DELETE | `/api/video/{videoId}/like` | 取消点赞（幂等） |
| GET | `/api/video/{videoId}/like` | 点赞状态 + 总数 |
| GET | `/api/video/liked-ids` | 我赞过的视频 ID |

> 历史弹幕 `GET /api/danmaku` 与「服务端代发」`POST /api/danmaku` 已随**弹幕不落库**删除。
> 现在 `/api/danmaku` 前缀下只剩 `POST /api/danmaku/ws-ticket`（见上「实时互动」）。

**调试 / 管理**（均需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sensitive/check`、`/filter`、`/count` | 敏感词检查 / 过滤 / 数量 |
| POST | `/api/sensitive/reload` | 词库热更新（3 秒节流，防 DoS） |
| POST | `/api/count/{targetId}/{type}`、GET `/api/count/{targetId}` | ⚠️ 早期演示接口，已被正式点赞接口取代 |

## 目录结构

```
src/main/java/com/live/
├── common/      统一返回体、业务异常、全局异常处理、DFA 敏感词过滤器、手机号脱敏、用户状态
├── config/      JWT 拦截器、WebSocket 配置、MyBatis-Plus 分页、密码编码器、短信配置与校验
├── controller/  10 个 Controller（30 个接口）
├── entity/      user / video / user_like / live_room / sensitive_word ...（danmaku 已移除）
├── mapper/      MyBatis-Plus Mapper（含推荐与对账 SQL）
├── service/     业务层（点赞、直播、推荐、弹幕校验、敏感词、短信、验证码、WS 票据）
├── task/        点赞计数定时对账 + 在线人数回写
├── vo/          视图对象（请求 DTO + 返回 VO，含 WS 弹幕载荷 DanmakuPayload）
└── websocket/   弹幕握手拦截器 + 分房间处理器

src/main/resources/
├── static/      6 个演示页面（HTML + 原生 JS）
└── sql/         建表 / 索引 / 修复脚本
```

## 已知边界

> 如实说明，避免误解。这些也是项目下一步的方向。

- **弹幕不落库（本轮改造，已知取舍）**：弹幕定位为「瞬时聊天流」，不走数据库。代价是
  ① 刷新页面后弹幕面板从空白开始，没有历史可补；② 未登录用户收不到任何弹幕
  （握手需要身份，原来还能靠历史接口看）；③ 没有「服务端代发弹幕」通道
  （那个接口原本只落库、不广播，落库取消后失去意义）。
  `danmaku` 表与建表脚本仍在仓库里，但代码已不再写入。详见 `docs/plan/danmaku-no-persist.md`。

- **不碰音视频**：视频采集与推流由前端负责（`getUserMedia` / OBS / SRS），后端只做**直播间状态 + 实时文字互动**。
- **MQ 已接入但是【默认关闭】**：`LikeCountPublisher` 有两个实现，靠 `app.mq.enabled` 二选一
  （详见「核心设计 §5」）：
  - `false`（默认）→ `SyncLikeCountPublisher`，在请求线程里同步算 Redis 计数，行为与接入前完全一致；
  - `true` → `RocketLikeCountPublisher`，发一条**按 videoId 分区的顺序消息**，由
    `VideoLikeCountConsumer` 异步算（带 messageId 幂等去重），接口返回值可能滞后几毫秒。
  打开方式：`mvn spring-boot:run -Dspring-boot.run.profiles=dev,mq`（`mq` profile 负责配 NameServer）。
  不管开还是关，`video.like_count` 都由**两级对账**兜底刷回（脏集合 30 秒一轮 + 每天凌晨全量）。
- **收藏 / 转发 / 评论**：表已建，功能未实现（设计储备）。
- **礼物榜只在 Redis**：`gift:rank:{roomId}` 未落库，Redis 淘汰即永久丢失；方案是补礼物流水表 + 异步落库。
- **WebSocket 会话表在单机内存**，多机部署需要换成 Redis Pub/Sub 或 MQ 广播。
- **在线人数是"近似值"**：靠心跳续期 + 60 秒超时剔除，客户端崩溃时最多延迟 60 秒才减下来（这是刻意取舍：
  靠客户端"上报离开"永远不可靠）。
- **没有 Swagger / Docker**：当前优先级是保证功能闭环与可演示；单元测试 34 个，覆盖登录安全与验证码链路。

## 相关文档

- [`README_LIVE.md`](README_LIVE.md) —— 直播模块设计（列表 / 开播关播 / 推荐 / 实时互动）
- [`README_RECOMMEND.md`](README_RECOMMEND.md) —— 视频模块与推荐设计
- [`TODO清单.md`](TODO清单.md) —— 待办、优化项与技术债（含实测数据与数据库体检记录）
- [`like-concurrency-test.js`](like-concurrency-test.js) / [`ws-test.js`](ws-test.js) —— 点赞并发压测与 WebSocket 冒烟脚本
- [`verify-fixes.js`](verify-fixes.js) —— 本轮修复的回归验证脚本（`node verify-fixes.js`，可用 `BASE` 指定后端地址）

---

> 个人学习与求职作品，代码注释里保留了关键设计取舍的推理过程（尤其 `VideoLikeService`、`LiveLikeService`、
> `JwtInterceptor`），方便对照理解。
