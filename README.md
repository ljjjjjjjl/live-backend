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
| Java 源文件 | 66 个 |
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
- [x] 在线人数：Set（`SADD` / `SREM` / `SCARD`）
- [x] 礼物榜：ZSet（`ZINCRBY` / `ZREVRANGE`）
- [x] 直播间人气：`INCRBY` + **Lua 限速（20 次/秒）**，开播归零、关播结算写回 MySQL
- [x] 弹幕：WebSocket 分房间广播，握手用 token 校验身份，异常回执而非静默失败

**视频与点赞**
- [x] 视频推荐（分类召回 + 热度排序 + 排除已看 + 冷启动兜底）
- [x] 视频点赞：权威表 `user_like` 同步写 + Redis 计数派生 + Lua 原子化 + 定时对账
- [x] 直播间点赞：纯计数器语义（不判重、不取消）

**内容审核**
- [x] DFA（Trie）敏感词过滤，词库启动全量加载 + 支持热更新

**工程化**
- [x] 参数校验（Jakarta Validation）+ 全局异常处理
- [x] Hikari 连接池显式配置（上限 20 + 10s 泄漏检测）
- [ ] 单元测试 / Swagger / Docker（未做）

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
        ① 脏集合对账（主力）SPOP 一批(默认 1000) → 只查这批 videoId 的真实赞数 → 只改对不上的行
                        │
                        ▼
        ② 全量对账（兜底）每天凌晨 3 点跑一遍 —— Redis 丢了脏集合也不会永久漏修
```

四个刻意的设计点：**Set 去重**（同一视频点 1 万次只留一个成员）、**用 `SPOP` 而不是 `SMEMBERS`+`SREM`**
（原子弹出，不会"取了还没删就崩"）、**限批**（避免对账自己攒成大事务）、
**`SPOP` 放在事务外**（事务回滚不会把弹出的 videoId 弄丢）。

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

# 2) 改配置：src/main/resources/application.yml（MySQL / Redis 连接）
# 3) 启动
mvn spring-boot:run
# 4) 打开演示页
#    http://localhost:8080/         登录 / 注册
#    http://localhost:8080/live.html 直播间列表    room.html?id=204 直播间详情
#    http://localhost:8080/home.html 视频推荐      like.html 点赞并发压测
```

> ⚠️ `application.yml` 里的数据库密码 / JWT 密钥是**本地演示用**的默认值，生产环境请用环境变量注入。

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
| POST | `/api/live/{roomId}/enter` | 进房（在线 +1） |
| POST | `/api/live/{roomId}/leave` | 离房（在线 -1） |
| GET | `/api/live/{roomId}/online` | 当前在线人数 · 游客可读 |
| POST | `/api/live/{roomId}/like` | 点赞（body `{"delta":N}`，可连点、限速 20/s） |
| GET | `/api/live/{roomId}/like` | 当前人气 · 游客可读 |
| POST | `/api/live/{roomId}/gift` | 送礼物（收礼者 = 房间主播，服务端推导） |
| GET | `/api/live/{roomId}/gift/rank` | 礼物榜 TopN · 游客可读 |
| WS | `/ws/danmaku/{roomId}?token=` | 实时弹幕 |

**视频与弹幕**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/recommend` | 视频推荐 · 游客可读 |
| POST | `/api/video/{videoId}/like` | 点赞（幂等） |
| DELETE | `/api/video/{videoId}/like` | 取消点赞（幂等） |
| GET | `/api/video/{videoId}/like` | 点赞状态 + 总数 |
| GET | `/api/video/liked-ids` | 我赞过的视频 ID |
| GET | `/api/danmaku` | 历史弹幕（分页）· 游客可读 |
| POST | `/api/danmaku` | 服务端代发弹幕（经 DFA 过滤） |

**调试 / 管理**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sensitive/check`、`/filter`、`/count` | 敏感词检查 / 过滤 / 数量 |
| POST | `/api/sensitive/reload` | 词库热更新 |
| POST | `/api/count/{targetId}/{type}`、GET `/api/count/{targetId}` | ⚠️ 早期演示接口，已被正式点赞接口取代 |

## 目录结构

```
src/main/java/com/live/
├── common/      统一返回体、业务异常、全局异常处理、DFA 敏感词过滤器
├── config/      JWT 拦截器、WebSocket 配置、MyBatis-Plus 分页、Redis 序列化、密码编码器
├── controller/  10 个 Controller（30 个接口）
├── entity/      user / video / user_like / live_room / danmaku / sensitive_word ...
├── mapper/      MyBatis-Plus Mapper（含推荐与对账 SQL）
├── service/     业务层（点赞、直播、推荐、弹幕、敏感词、短信、验证码）
├── task/        点赞计数定时对账
├── vo/          视图对象
└── websocket/   弹幕握手拦截器 + 分房间处理器

src/main/resources/
├── static/      6 个演示页面（HTML + 原生 JS）
└── sql/         建表 / 索引 / 修复脚本
```

## 已知边界

> 如实说明，避免误解。这些也是项目下一步的方向。

- **不碰音视频**：视频采集与推流由前端负责（`getUserMedia` / OBS / SRS），后端只做**直播间状态 + 实时文字互动**。
- **MQ 未接入**：点赞的异步刷回只留了 `LikeCountPublisher` 接口 + `NoopLikeCountPublisher` 空实现，
  `video.like_count` 目前完全靠**两级对账**兜底（脏集合 30 秒一轮 + 每天凌晨全量兜底，见「核心设计 §4」）。
- **收藏 / 转发 / 评论**：表已建，功能未实现（设计储备）。
- **礼物榜只在 Redis**：`gift:rank:{roomId}` 未落库，Redis 淘汰即永久丢失；方案是补礼物流水表 + 异步落库。
- **`live_room.online_count` 是静态列**：真值在 Redis，该列仅用于排序兜底，未接实时刷回。
- **WebSocket 会话表在单机内存**，多机部署需要换成 Redis Pub/Sub 或 MQ 广播。
- **无单元测试 / Swagger / Docker**：当前优先级是保证功能闭环与可演示。

## 相关文档

- [`README_LIVE.md`](README_LIVE.md) —— 直播模块设计（列表 / 开播关播 / 推荐 / 实时互动）
- [`README_RECOMMEND.md`](README_RECOMMEND.md) —— 视频模块与推荐设计
- [`TODO清单.md`](TODO清单.md) —— 待办、优化项与技术债（含实测数据与数据库体检记录）
- [`like-concurrency-test.js`](like-concurrency-test.js) / [`ws-test.js`](ws-test.js) —— 点赞并发压测与 WebSocket 冒烟脚本

---

> 个人学习与求职作品，代码注释里保留了关键设计取舍的推理过程（尤其 `VideoLikeService`、`LiveLikeService`、
> `JwtInterceptor`），方便对照理解。
