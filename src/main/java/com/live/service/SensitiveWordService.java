package com.live.service;

import com.live.common.DfaSensitiveFilter;
import com.live.entity.SensitiveWord;
import com.live.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 敏感词业务：负责从数据库加载词库到 {@link DfaSensitiveFilter}，并支持热更新。
 *
 * <p>启动流程：{@link #loadFromDb()} 在 Bean 初始化后（@PostConstruct）读取 sensitive_word 表
 * 全部词 → {@link DfaSensitiveFilter#reload(List)} 构建 Trie。这样应用一启动，过滤器即就绪。
 *
 * <p>热更新：词库（表）变更后，调用 {@link #reload()} 重建 Trie，无需重启应用。
 * 由于 DfaSensitiveFilter 采用"构建新树 + 原子替换引用"，重建不会影响正在进行的匹配。
 *
 * <h3>本轮修复：{@code check} 和 {@code filter} 的归一化必须一致</h3>
 * <p>原来只有 {@link #check} 做了"转小写 + 去空白"，而管理接口的 {@code /filter}
 * 直接拿原文去替换 —— 于是同一个 "N M S L"，{@code check} 判定命中（正确），
 * {@code filter} 却替换不掉（错误）。两个能力对同一个输入给出不同结论，
 * 是最难排查的一类 bug。现在两者共用同一个 {@link #normalize}。
 */
@Slf4j
@Service
public class SensitiveWordService {

    /** 归一化时要去掉的空白：空格/换行/制表/全角空格 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");

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
     *
     * <p>词条统一转小写，匹配时文本也转小写，避免 "SB" 这种大小写变体绕过。
     * 一定要指定 {@link Locale#ROOT}：土耳其语区域下 {@code "I".toLowerCase()} 会变成 "ı"，
     * 词库里的小写 "i" 就永远匹配不到了。
     */
    public List<String> loadFromDb() {
        return sensitiveWordMapper.selectList(null).stream()
                .map(SensitiveWord::getWord)
                .filter(java.util.Objects::nonNull)
                .map(w -> w.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * 校验文本是否命中敏感词：命中返回命中的词，否则返回 null。
     * 供业务层（如弹幕发送）调用，命中时业务层抛异常或替换。
     *
     * @param text 原始文本（内部会先归一化）
     */
    public String check(String text) {
        if (text == null) {
            return null;
        }
        return dfaSensitiveFilter.match(normalize(text));
    }

    /**
     * 把命中敏感词的部分替换成 {@code **}（管理接口 / 将来"替换后放行"策略用）。
     *
     * <p>注意：返回值是<b>归一化之后</b>的文本（小写、无空白），
     * 也就是说它不适合直接替换用户原文展示 —— 这是调试接口，保持"和 check 口径一致"更重要。
     */
    public String filter(String text) {
        if (text == null) {
            return null;
        }
        return dfaSensitiveFilter.filter(normalize(text));
    }

    /**
     * 归一化：转小写 + 去掉所有空白。
     *
     * <p>为什么只做这两类：它们是最廉价、最不会误伤正常内容的两种绕过手段。
     * 更复杂的（全角/拼音/形近字）属于风控策略问题，不是这里该解决的。
     */
    private String normalize(String text) {
        return WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * 返回当前已加载的敏感词数量（用于日志/监控）。
     */
    public int wordCount() {
        return dfaSensitiveFilter.getWordCount();
    }
}
