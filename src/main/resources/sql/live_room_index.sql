-- ============================================================
-- 直播间列表索引（配合分页接口）
-- 数据库：db1　执行：Navicat / IDEA Database / mysql 命令
-- 作用：直播间列表默认 `WHERE is_living=1 [AND category=?] ORDER BY online_count DESC`
--       最常用的查询，加联合索引让排序/过滤都能走索引，避免全表扫。
-- ============================================================
USE db1;

-- 主索引：直播中 + 分区 + 在线人数（覆盖"只看直播中的 + 按分区过滤 + 按在线排序"）
-- 若 category 过滤可空，这个索引在"不带 category"时也能用前缀 (is_living, online_count)? 
-- 实际上 ORDER BY online_count 在 (is_living, category, online_count) 上，
-- 不带 category 时只能用到 is_living 前缀，排序仍可能 filesort。所以再补一个只按 in_living 的。
ALTER TABLE live_room
  ADD INDEX idx_living_online (is_living, online_count),
  ADD INDEX idx_living_category_online (is_living, category, online_count);
