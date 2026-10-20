-- ============================================================================
-- 用户表安全迁移：新增账号状态列（2026-10-09）
--
-- 背景：登录接口此前**不检查账号状态**，封禁用户只要密码正确就能继续登录并拿到 JWT。
--       `user` 表原本没有状态列，因此本脚本补上；默认 NORMAL（存量数据全部落成可登录）。
--
-- 执行：mysql -uroot -p123456 db1 < src/main/resources/sql/user_security_20261009.sql
-- ⚠️ MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，重复执行会报 1060（Duplicate column name），
--    属预期；执行前可用下面第二条语句确认列是否已存在。
-- ============================================================================

-- ① （可选）执行前自检：若返回 0 行说明还没有 status 列
SELECT COUNT(*) AS status_column_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'db1' AND TABLE_NAME = 'user' AND COLUMN_NAME = 'status';

-- ② 新增状态列：NORMAL / BANNED / DELETED / DISABLED / LOCKED
ALTER TABLE `user`
    ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
    COMMENT '账号状态：NORMAL可登录/BANNED封禁/DELETED注销/DISABLED停用/LOCKED锁定'
    AFTER `phone`;

-- ③ 存量数据兜底（列默认值已经是 NORMAL，这里保证历史 NULL/空串也被修正）
UPDATE `user` SET `status` = 'NORMAL' WHERE `status` IS NULL OR `status` = '';

-- ④ 确认唯一约束仍在（手机号唯一性靠它兜底并发换绑）
SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'db1' AND TABLE_NAME = 'user'
ORDER BY INDEX_NAME;
