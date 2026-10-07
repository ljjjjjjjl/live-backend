-- ============================================================
-- 直播推荐系统 · 建表 + 演示数据（live_room + live_user_watch）
-- 数据库：db1  执行：Navicat / IDEA Database / mysql 命令
--
-- 直播间推荐和视频推荐同构，只是对象从"视频"换成"直播间"。
-- 直播推荐的关键差异：只推"正在直播中(is_living=1)"的直播间。
-- ============================================================

USE db1;

-- ------------------------------------------------------------
-- ① 直播间表 live_room
--    推荐依赖字段：category(分区) / online_count(在线人数) / is_living(是否直播中)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS live_room (
  id            BIGINT       PRIMARY KEY COMMENT '直播间ID（演示用手工id）',
  title         VARCHAR(200) NOT NULL COMMENT '直播标题',
  cover         VARCHAR(500) COMMENT '封面图',
  anchor_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '主播ID（关联 user.id）',
  category      VARCHAR(50)  NOT NULL DEFAULT '其他' COMMENT '直播分区，推荐核心特征',
  tags          VARCHAR(255) NOT NULL DEFAULT '' COMMENT '标签，逗号分隔',
  online_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '当前在线人数（直播热度核心）',
  like_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '人气/热度（权重之一）',
  is_living     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=直播中 0=下播（推荐只推直播中的）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开播时间',
  INDEX idx_category (category),
  INDEX idx_online  (online_count)
) ENGINE=InnoDB COMMENT='直播间表';

-- ------------------------------------------------------------
-- ② 用户观看直播间记录表 live_user_watch
--    推荐的行为数据：用户看过哪些直播间 → 反查这些直播间常看的分区
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS live_user_watch (
  id          BIGINT   PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT   NOT NULL COMMENT '用户ID',
  room_id     BIGINT   NOT NULL COMMENT '直播间ID',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '观看时间',
  UNIQUE KEY uk_user_room (user_id, room_id),  -- 一人一对一直播间最多一条观看记录
  KEY idx_user_time (user_id, create_time)
) ENGINE=InnoDB COMMENT='用户观看直播间记录表';

-- ------------------------------------------------------------
-- ③ 演示数据（可重复执行：先清空再插入）
--    覆盖 5 个分区，共 14 个直播间（含 2 个已下播，用于验证"只推直播中"）
-- ------------------------------------------------------------
TRUNCATE TABLE live_room;
TRUNCATE TABLE live_user_watch;

INSERT INTO live_room (id, title, cover, anchor_id, category, tags, online_count, like_count, is_living, create_time) VALUES
-- 游戏区
(201,'王者荣耀巅峰赛冲分','https://picsum.photos/seed/l201/400/225', 1,'游戏','王者荣耀,巅峰赛,上分', 32000, 88000, 1, NOW()),
(202,'永劫无间高手直播','https://picsum.photos/seed/l202/400/225', 2,'游戏','永劫无间,吃鸡', 15000, 42000, 1, NOW()),
(203,'原神深渊满星教学','https://picsum.photos/seed/l203/400/225', 5,'游戏','原神,教学,深渊', 9000, 26000, 1, NOW()),
-- 娱乐区
(204,'深夜聊天室：聊聊生活','https://picsum.photos/seed/l204/400/225', 3,'娱乐','聊天,陪伴,深夜', 45000, 120000, 1, NOW()),
(205,'才艺展示：唱歌点歌','https://picsum.photos/seed/l205/400/225', 3,'娱乐','唱歌,才艺,点歌', 28000, 76000, 1, NOW()),
-- 知识区
(206,'投资小白入门课','https://picsum.photos/seed/l206/400/225', 5,'知识','理财,投资,教学', 12000, 34000, 1, NOW()),
(207,'职场技能：Excel进阶','https://picsum.photos/seed/l207/400/225', 6,'知识','Excel,办公,职场', 8000, 22000, 1, NOW()),
-- 户外区
(208,'城市街头漫步','https://picsum.photos/seed/l208/400/225', 9,'户外','户外,漫步,vlog', 18000, 50000, 1, NOW()),
(209,'海边钓鱼直播','https://picsum.photos/seed/l209/400/225', 9,'户外','钓鱼,海边,户外', 6000, 17000, 1, NOW()),
-- 音乐区
(210,'钢琴即兴演奏','https://picsum.photos/seed/l210/400/225', 10,'音乐','钢琴,演奏,即兴', 20000, 55000, 1, NOW()),
(211,'民谣吉他弹唱','https://picsum.photos/seed/l211/400/225', 10,'音乐','吉他,民谣,弹唱', 11000, 30000, 1, NOW()),
-- 电竞区（跨分类补充）
(212,'英雄联盟职业选手','https://picsum.photos/seed/l212/400/225', 1,'游戏','LOL,职业,电竞', 38000, 100000, 1, NOW()),
-- 已下播的两个（用于验证：推荐不会出现它们）
(213,'昨天回放：游戏复盘','https://picsum.photos/seed/l213/400/225', 2,'游戏','回放,复盘', 5000, 12000, 0, NOW()),
(214,'过期预告：下周三见','https://picsum.photos/seed/l214/400/225', 7,'影视','预告,下架', 3000, 8000, 0, NOW());

-- 给用户1造观看记录（让个性化推荐生效）
-- 用户1 → 看过 206(知识) 和 207(职场/知识) → 推荐应优先推"知识"分类的直播间
INSERT INTO live_user_watch (user_id, room_id) VALUES
(1,206),(1,207);
