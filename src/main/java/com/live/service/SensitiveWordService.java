package com.live.service;

import com.live.common.DfaSensitiveFilter;
import com.live.entity.SensitiveWord;
import com.live.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 敏感词业务：负责从数据库加载词库到 {@link DfaSensitiveFilter}，并支持热更新。
 *
 * <p>启动流程：{@link #loadFromDb()} 在 Bean 初始化后（@PostConstruct）读取 sensitive_word 表
 * 全部词 → {@link DfaSensitiveFilter#reload(List)} 构建 Trie。这样应用一启动，过滤器即就绪。
 *
 * <p>热更新：词库（表）变更后，调用 {@link #reload()} 重建 Trie，无需重启应用。
 * 由于 DfaSensitiveFilter 采用"构建新树 + 原子替换引用"，重建不会影响正在进行的匹配。
 */
@Slf4j
@Service
public class SensitiveWordService {

    @Autowired
    private SensitiveWordMapper sensitiveWordMapper;

    @Autowired
    private DfaSensitiveFilter dfaSensitiveFilter;

    /**
     * 应用启动后自动加载词库（Bean 初始化完成后执行一次）。
     */
    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 从数据库读取所有敏感词并重建 DFA 过滤器（启动 & 热更新共用）。
     */
    public void reload() {
        List<String> words = loadFromDb();
        dfaSensitiveFilter.reload(words);
        log.info("DFA 敏感词过滤器已加载 {} 个敏感词", words.size());
    }

    /**
     * 从数据库加载词库（返回纯词列表）。
     */
    public List<String> loadFromDb() {
        // selectList(null) 取全表；词加个小写归一化，匹配时也统一小写，避免大小写绕过
        return sensitiveWordMapper.selectList(null).stream()
                .map(SensitiveWord::getWord)
                .map(String::toLowerCase)
                .toList();
    }

    /**
     * 校验文本是否命中敏感词：命中返回命中的词，否则返回 null。
     * 供业务层（如弹幕发送）调用，命中时业务层抛异常或替换。
     *
     * <p>归一化：先把待检测文本【转小写 + 去所有空白】，与词库（存小写）对齐，
     * 从而拦截 "SB"（大写）、"N M S L"（加空格）、" nmsl " 等变体绕过——否则
     * 词库存的是小写 sb/nmsl，全大写/带空格就匹配不到。
     */
    public String check(String text) {
        if (text == null) {
            return null;
        }
        // 转小写 + 去掉所有空白（空格/换行/制表），只做这两类归一化，避免误伤正常内容
        String normalized = text.toLowerCase().replaceAll("\\s+", "");
        return dfaSensitiveFilter.match(normalized);
    }

    /**
     * 返回当前已加载的敏感词数量（用于日志/监控）。
     */
    public int wordCount() {
        return dfaSensitiveFilter.getWordCount();
    }
}
