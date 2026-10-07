-- ============================================================
-- 视频表索引（配合分区列表 / 推荐冷启动接口）
-- 数据库：db1　执行：Navicat / IDEA Database / mysql 命令
--
-- 为什么要加：video 是推荐系统的核心表，两条热路径都栽在 ORDER BY 上——
--   ① 分区列表    WHERE category=? ORDER BY play_count DESC LIMIT 8
--      只有 idx_category 时：能过滤，但排序要 filesort（先捞出整分区再排）
--   ② 冷启动热度  ORDER BY (play_count + like_count*5 + favorite_count*10
--                          + comment_count*8 + forward_count*6) DESC LIMIT 8
--      表达式排序没有任何索引可用 → possible_keys=NULL + Using filesort
-- ============================================================
USE db1;

-- ① 分区 + 播放量降序：把"过滤 + 排序"一次走完，消掉 filesort
ALTER TABLE video
  ADD INDEX idx_category_play (category, play_count DESC);

-- ② 热度表达式的函数索引（MySQL 8.0.13+ 支持）
--    ⚠️ 表达式必须与 VideoMapper.selectHotExclude 和 Video.heatScore() 里写的**完全一致**，
--       以后改热度权重时是"三处一起改"，漏一处索引就静默失效（不会被选中，但仍占写入开销）。
ALTER TABLE video
  ADD INDEX idx_heat ((play_count + like_count*5 + favorite_count*10 + comment_count*8 + forward_count*6));

-- ③ idx_play_count 是否还需要，取决于有没有"不带 category、只按 play_count 排序"的查询。
--    目前代码里没有（分区列表走 idx_category_play，热度排序走 idx_heat），
--    确认后可以删掉，少一份写放大：
-- ALTER TABLE video DROP INDEX idx_play_count;

-- ------------------------------------------------------------
-- 实测数据（30 万行合成表对照，2026-10-07）
--   热度排序：建索引前 Table scan 300000 行 + Sort = 189 ms
--             建索引后 Index scan using idx_heat (reverse) = 0.24 ms   ≈ 780×
--   分区列表：建索引前 全表扫 + Filter = 160 ms
--             建索引后 Index lookup using idx_category_play = 1.63 ms   ≈ 100×
--   （说明：真实表只有 20 行时优化器仍会选全表扫描，那是正确选择，不代表索引没用）
-- ------------------------------------------------------------
