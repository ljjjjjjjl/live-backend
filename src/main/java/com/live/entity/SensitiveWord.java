package com.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感词实体，对应 sensitive_word 表。
 *
 * <p>这是 DFA 敏感词过滤的"词库"来源：启动时把这张表的词全部加载进
 * {@code DfaSensitiveFilter} 构建 Trie，匹配时文本只扫一遍即可命中。
 * 支持热更新：表内容变更后手动触发 reload() 重建 Trie，无需重启。
 */
@Data
@TableName("sensitive_word")
public class SensitiveWord {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感词（建议存纯净形态：小写 + 去空格，便于匹配） */
    @NotBlank(message = "敏感词不能为空")
    @Size(max = 100, message = "敏感词长度不能超过100")
    private String word;

    /** 创建时间 */
    private LocalDateTime createTime;
}
