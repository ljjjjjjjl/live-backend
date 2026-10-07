-- 弹幕表（第 2 周 WebSocket 弹幕）
-- 核心索引：idx_room_time(room_id, id) —— 既满足"按房间拉历史弹幕"，
-- 又满足"按 id 倒序分页"（最新的在前面）。这就是典型的"复合索引做查询 + 排序"。
CREATE TABLE IF NOT EXISTS `danmaku` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '弹幕ID',
    `room_id`     BIGINT       NOT NULL                COMMENT '直播间ID（关联 live_room.id）',
    `user_id`     BIGINT       DEFAULT NULL            COMMENT '发送用户ID（关联 user.id，空=游客）',
    `nickname`    VARCHAR(50)  DEFAULT NULL            COMMENT '发送人昵称（冗余，免连表）',
    `content`     VARCHAR(255) NOT NULL                COMMENT '弹幕内容',
    `color`       VARCHAR(20)  DEFAULT NULL            COMMENT '弹幕颜色(如 #ff0000)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_room_time` (`room_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='弹幕表';
