package com.live.common;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DFA（确定性有限自动机）敏感词过滤器。
 *
 * <p>原理：把所有敏感词构建成一棵 Trie 字典树（每个字符一个节点、词结尾打标），
 * 之后对一段文本从左到右只扫一遍、顺着树走，命中"词尾"即识别出敏感词。
 * 时间复杂度 O(文本长度)，与敏感词数量基本无关——比 N 个词逐个 contains 快得多。
 *
 * <p><b>线程安全 & 热更新</b>：本类用一个不可变的 root 引用，{@link #reload(List)}
 * 时构建一颗新树后再原子替换 root 引用；匹配是纯读操作，不修改树。所以：
 * <ul>
 *   <li>全量替换（重新构建）不会影响正在进行的匹配（读旧树），无需加锁。</li>
 *   <li>匹配方法可被多线程并发调用，安全。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   filter.reload(List.of("赌博", "色情"));
 *   String hit = filter.match("这里有赌博网站");   // 返回 "赌博"
 *   boolean ok = filter.contains("这里有赌博");      // true
 *   String fixed = filter.filter("赌博网站");        // "**网站"
 * </pre>
 */
@Component
public class DfaSensitiveFilter {

    /** Trie 节点：next = 当前字符 -> 下一节点；isEnd 表示这里结束一个词 */
    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        boolean isEnd = false;
    }

    /** 当前 Trie 根。用 volatile + 引用替换实现"可见性 + 原子切换"，线程安全。 */
    private volatile Node root = new Node();

    /** 敏感词数量（用于日志/监控） */
    private volatile int wordCount = 0;

    public int getWordCount() {
        return wordCount;
    }

    /**
     * 用一批敏感词重建整棵 Trie（热更新入口）。
     * 先建新树，成功后原子替换 root，旧树等引用释放后被 GC。
     *
     * @param words 敏感词集合，可空（空则清空过滤器）
     */
    public void reload(List<String> words) {
        Node newRoot = new Node();
        int count = 0;
        if (words != null) {
            for (String w : words) {
                if (w == null || w.isEmpty()) {
                    continue;
                }
                addWord(newRoot, w.trim());
                count++;
            }
        }
        // 先写 wordCount，再原子替换 root——匹配(读)永远拿到一棵"当前一致的树"
        this.root = newRoot;
        this.wordCount = count;
    }

    /** 往指定根上插入一个词（构建 Trie 用） */
    private void addWord(Node root, String word) {
        Node cur = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            cur = cur.next.computeIfAbsent(c, k -> new Node());
        }
        cur.isEnd = true;
    }

    /**
     * 判断文本里是否命中任一敏感词。
     *
     * @return true 命中
     */
    public boolean contains(String text) {
        return match(text) != null;
    }

    /**
     * 扫描文本，返回第一个命中的敏感词；没有则返回 null。
     * 每个起点都顺着树走，用一个"最长匹配"策略：从起点到第一个词尾就返回（最短命中）。
     * 若要命中全部/更全，用 {@link #matchAll(String)}。
     */
    public String match(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Node cur = root;
        for (int i = 0; i < text.length(); i++) {
            // 从 i 为起点，尝试沿树匹配
            Node node = cur;
            int j = i;
            while (j < text.length()) {
                node = node.next.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.isEnd) {
                    return text.substring(i, j + 1);
                }
                j++;
            }
        }
        return null;
    }

    /**
     * 扫描文本，返回所有命中的敏感词（去重）。适合"列出全部违规词"。
     */
    public List<String> matchAll(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        for (int i = 0; i < text.length(); i++) {
            Node node = root;
            int j = i;
            while (j < text.length()) {
                node = node.next.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.isEnd) {
                    String hit = text.substring(i, j + 1);
                    if (!result.contains(hit)) {
                        result.add(hit);
                    }
                }
                j++;
            }
        }
        return result;
    }

    /**
     * 把文本里所有命中的敏感词替换成 {@code replace}（默认 "***"），返回处理后的文本。
     * 例如 filter("赌博网站") -> "**网站"（用一个字符的星号替代命中长度，直观展示）。
     */
    public String filter(String text) {
        return filter(text, "**");
    }

    /**
     * 用指定替换串把文本里命中的敏感词替换掉。
     */
    public String filter(String text, String replace) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String mask = replace == null ? "***" : replace;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            Node node = root;
            int j = i;
            boolean hit = false;
            int hitLen = 0;
            while (j < text.length()) {
                node = node.next.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.isEnd) {
                    hit = true;
                    hitLen = j - i + 1;   // 记录命中的词长度
                }
                j++;
            }
            if (hit) {
                sb.append(mask);           // 用替换串替代命中的词
                i += hitLen;               // 跳过被替换的词，继续看后面
            } else {
                sb.append(text.charAt(i)); // 非敏感词原样保留
                i++;
            }
        }
        return sb.toString();
    }
}
