-- ============================================================
-- db1 数据/元数据修复（2026-10-07 数据库体检）
-- 数据库：db1　执行：Navicat / IDEA Database / mysql 命令
--
-- 本次修的三类问题：
--   ① video 表 4 条列注释 + category 默认值是乱码（GBK 脚本被按 utf8mb4 导入导致）
--      —— 危害最大的是默认值：'鍏朵粬' 不是 '其他'，INSERT 不传 category 就会写进一个
--         谁也匹配不上的分区，之后按"其他"筛选/推荐全部落空
--   ② live_room.is_living 默认值是 1（INSERT 不传就是"直播中"），改 0 更安全
--   ③ 种子数据里 4 个主播各开着 2 个直播间，违反"一个主播同时只开一个播"的业务规则
--      （该规则只在 LiveRoomService.closeOtherLiving() 里兜底，直接 INSERT 的种子数据绕过了它）
--
-- 执行前的备份（本次体检时导出的，可回滚）：
--   .dbbackup/db1_20261007.sql、comment_bak_20260910.sql、video_app_20261007.sql
-- ============================================================
USE db1;

-- ---------- ① video 表：恢复被写坏的注释与默认值 ----------
ALTER TABLE video
  MODIFY author_id  bigint       NOT NULL DEFAULT 0      COMMENT '作者/UP主ID（关联 user.id）',
  MODIFY category   varchar(50)  NOT NULL DEFAULT '其他'  COMMENT '分区/分类，推荐的核心特征',
  MODIFY tags       varchar(255) NOT NULL DEFAULT ''     COMMENT '标签，逗号分隔，如 游戏,电竞',
  MODIFY play_count bigint       NOT NULL DEFAULT 0      COMMENT '播放量（热度权重之一）';

-- ---------- ② live_room：is_living 默认值 1 → 0 ----------
-- LiveRoomService.create()/start() 里都是显式 setIsLiving(1)，改默认值不影响现有逻辑，
-- 只是避免"漏传字段就凭空多出一场直播"。
ALTER TABLE live_room
  MODIFY is_living tinyint NOT NULL DEFAULT 0 COMMENT '1=直播中 0=下播（推荐只推直播中的）';

-- ---------- ③ 种子数据：一个主播只保留一个"直播中" ----------
-- 保留规则：同主播直播中的直播间里，人气（online_count）最高的那一场；并列时取 id 最小的。
-- 用临时表是为了绕开 MySQL "不能在 UPDATE 的子查询里引用被更新的表"（错误 1093）。
CREATE TEMPORARY TABLE tmp_keep_living AS
SELECT id FROM (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY anchor_id ORDER BY online_count DESC, id ASC) AS rn
  FROM live_room
  WHERE is_living = 1
) t WHERE rn = 1;

UPDATE live_room
SET is_living = 0
WHERE is_living = 1
  AND id NOT IN (SELECT id FROM tmp_keep_living);

DROP TEMPORARY TABLE tmp_keep_living;

-- 核对：下面这条应该查不出任何行
-- SELECT anchor_id, COUNT(*) FROM live_room WHERE is_living=1 GROUP BY anchor_id HAVING COUNT(*)>1;

-- ---------- ④ 遗留对象清理（本次已执行，备份见文件头）----------
-- DROP DATABASE IF EXISTS video_app;                 -- 早期废弃库，7 张空表，代码里零引用
-- DROP TABLE IF EXISTS db1.comment_bak_20260910;     -- 9-10 的评论表备份
