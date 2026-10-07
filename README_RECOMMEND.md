# 🎯 推荐系统模块说明（live-backend）

> 用处：README + 面试讲解提纲。这个模块是**工程版推荐**，不是算法模型，定位是简历里的"数据思维 + 查询优化"亮点。

## 一、模块开了什么接口

```
GET /api/recommend?userId=1&size=10     → 返回 List<VideoVO>
```

- `userId`：当前用户。**带登录用户才能做到"千人千面"**；不带/未登录走全站热门兜底。
  > ⚠️ 这个 `userId` 目前是**前端 query 传参**（`RecommendController` 用 `@RequestParam`），
  > 且 `/api/recommend/**` 在 `WebConfig` 白名单里、不走 JWT 拦截器 —— 所以**它拿不到 Token 身份**，
  > 理论上可以被伪造。与"点赞/开播/发弹幕身份一律取 Token"不一致，属已知待办（见 `TODO清单.md`）。
- `size`：要几条，默认 20，最大 50（`Math.min(size, 50)`）。
- 返回字段：id / title / cover / category / playCount / **likeCount** / heatScore / **reason**（推荐理由）。

## 二、推荐是怎么一步步做的（对应真实推荐流程的【召回 → 排序】）

| 步骤 | 说明 | 对应代码 |
|---|---|---|
| 1 冷启动判断 | 没登录 / 没任何点赞行为 → 直接返回全站热门 | `RecommendService.recommend()` |
| 2 兴趣召回 | 查该用户**点过赞**的视频 → 反查它们的分类 → 聚合成"用户最爱的分类"（最多取前 3 个） | `VideoMapper.selectUserCategoryPreference()` |
| 3 同类召回 | 按最爱的分类拉取视频（**排序键是 `play_count DESC`**），**排除已看过**的 | `VideoMapper.selectByCategoryExcludeAndOrderByHot()` |
| 4 排序 + 兜底 | 若不够条数用**全站热门**补齐，热门按热度表达式降序 | `VideoMapper.selectHotExclude()` + `Video.heatScore()` |

> ⚠️ **排序键别讲错**：**只有冷启动/兜底**（`selectHotExclude`）用热度表达式排序；
> **分区召回**（`selectByCategoryExcludeAndOrderByHot`）用的是 `ORDER BY play_count DESC`，
> 并没有用热度分。`Video.heatScore()` 目前只用于 `VideoVO.heatScore` 字段展示。
> 两处 SQL 与 Java 各写了一遍权重，改权重时三处要同步（建议抽常量）。

## 三、热度分怎么算（这就是"为什么这样排序"）

```java
热度 = play_count*1 + like_count*5 + favorite_count*10 + comment_count*8 + forward_count*6
```

- **播放量权重最低**：播放最容易刷，说明不了多喜欢。
- **收藏/转发/点赞权重高**：代表真实正向兴趣。
- 所以哪怕一个视频播放量少，但如果收藏多，热度分也能上去——排行榜不只看"点击量"。

## 四、为什么有了推荐，你面试能加分

1. **召回 + 排序**：有了"信息流生成"的完整概念，不再是简单 `SELECT ... ORDER BY time`。
2. **从行为反推兴趣**：用 `user_like` 关联 `video` 按 `category` 聚合，是**协同过滤/内容召回的最简雏形**，能讲清数据流。
3. **冷启动 + 去重**：两个面试高频点都覆盖了（新用户怎么办？重复推荐怎么办？）。
4. **"已赞集合一次拿全"**：`GET /api/video/liked-ids` 一次返回该用户全部已赞视频 ID，列表页在前端做匹配，避免 20 条卡片发 20 次请求。
   > ⚠️ 目前**没有**"我的点赞列表 + 游标分页"接口：`likedVideoIds()` 是全量 `selectList` 后 map，
   > 重度用户几万赞会把内存撑起来（已记在 `TODO清单.md`：加上限 + `NOT IN` 改写）。面试别把"游标分页"说成已落地。

## 五、面试时别主动引到这些（答不好会被动）

- 协同过滤的数学推导、embedding 训练、FM/Wide&Deep 模型
- 特征工程、归一化、A/B 测试平台
- 这些是算法岗/推荐岗深挖范畴，Java 后端问概率低，答不出容易被扣分。**主动把话题引导到"缓存、SQL、并发、分页、冷启动"这些工程面上。**

## 六、怎么跑起来

```bash
# ① 建库建表 + 灌演示数据（video 表需要推荐特征字段，这里已准备好）
mysql -uroot -p123456 < src/main/resources/sql/recommend_init.sql
#   或者在 Navicat / IDEA Database 里直接执行这个 sql 文件

# ② 启动
mvn spring-boot:run

# ③ 调接口（两种都试试，看推荐差异）
curl "http://localhost:8080/api/recommend?size=5"            # 冷启动：全站热门
curl "http://localhost:8080/api/recommend?userId=1&size=8"   # 个性化：按用户1兴趣召回
```

## 七、后续可扩展（简历/项目可继续写）

- **行为埋点**：记录 view / duration（停留时长）→ 比点赞更强的兴趣信号
- **标签级召回**：从 `tags` 而不是 `category` 召回 → 更细粒度
- **Redis 缓存**：热门列表 + 用户兴趣画像存 Redis，扛高并发
- **多路召回 + 排序打分**：真实工程化方向
