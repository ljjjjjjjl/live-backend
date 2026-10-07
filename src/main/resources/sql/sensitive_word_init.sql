-- 敏感词表（第 3 周 DFA 敏感词过滤）
-- 说明：
--   1) 词库从这张表加载，DfaSensitiveFilter 启动时 select 全部并构建 Trie。
--   2) 支持"热更新"：表内容变了，手动触发 SensitiveWordService.reload() 重建 Trie，无需重启。
--   3) 词尽量存"纯净形态"（无空格/无大小写差异），匹配前的预处理在代码里做（转小写/去空格）。
CREATE TABLE IF NOT EXISTS `sensitive_word` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `word`        VARCHAR(100) NOT NULL                COMMENT '敏感词（去空格、小写存储）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)   -- 词不允许重复，避免 Trie 里重复插入
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='敏感词库';

-- 演示用敏感词（生产从后台/导入，这里先插几条便于测试）
INSERT INTO `sensitive_word` (`word`) VALUES
    ('赌博'),
    ('色情'),
    ('诈骗'),
    ('毒品'),
    ('暴力'),
    ('枪支'),
    ('反动');
-- 幂等：某次重复执行不会因为已有主键而报错（重复词靠 uk_word 幂等忽略）

-- ============================================================
-- 🔄 追加：英文 / 缩写 / 拼音 / 数字谐音 类敏感词（你已执行过上面的词，这段单独再执行）
--    使用 INSERT IGNORE：已存在的词(uk_word)会跳过，不会报错；只插入新增词。
--    英文/缩写一律存【小写】，配合代码里 check() 的 toLowerCase 归一化，可防大小写/加空格绕过。
-- ============================================================
INSERT IGNORE INTO `sensitive_word` (`word`) VALUES
    -- 英文 / 缩写（存小写）
    ('sb'), ('nmsl'), ('cnm'), ('tmd'), ('bt'), ('ass'), ('fuck'), ('fuckyou'),
    -- 拼音
    ('dubo'), ('seqing'), ('shenguan'), ('aizimei'),
    -- 数字谐音
    ('250'), ('7456'), ('sb250'), ('7481'),
    -- 中文补充
    ('裸聊'), ('博彩'), ('私服'), ('外挂'), ('代练');
