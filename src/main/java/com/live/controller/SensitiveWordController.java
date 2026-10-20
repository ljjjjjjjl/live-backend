package com.live.controller;

import com.live.common.Result;
import com.live.service.SensitiveWordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 敏感词 DFA 测试/管理接口（第 3 周）。
 *
 * <p>用途：方便手动验证 DFA 过滤效果 + 触发词库热更新（生产可用 Spring Security + 权限限制）。
 * <ul>
 *   <li>GET  /api/sensitive/check?text=xxx        —— 文本是否命中敏感词，返回命中的词</li>
 *   <li>GET  /api/sensitive/filter?text=xxx       —— 返回把敏感词替换成 ** 后的文本</li>
 *   <li>GET  /api/sensitive/count                 —— 当前已加载的敏感词数量</li>
 *   <li>POST /api/sensitive/reload                —— 重新从数据库加载词库（热更新）</li>
 * </ul>
 *
 * <p>业务实际接入点在 {@code DanmakuService.save}（发弹幕前校验），这里只是管理/调试通道。
 *
 * <h3>权限（本轮修复）</h3>
 * <p>这几个接口原来<b>完全不需要登录</b>（{@code WebConfig} 的拦截范围里没有 {@code /api/sensitive/**}），
 * 于是 {@code POST /reload} 成了一个免费的 DoS 入口：一次调用 = 全表读 + 重建整棵 Trie，
 * 循环调用足以把 CPU 和数据库打满；{@code GET /check} 还会把<b>命中的词</b>返回给调用方，
 * 等于对外提供"敏感词探测器"，反复试探可以反推出词库内容。
 * 现在：① {@code WebConfig} 把 {@code /api/sensitive/**} 纳入 JWT 拦截；
 * ② {@link #reload} 额外做了 3 秒一次的进程内节流。
 */
@Slf4j
@RestController
@RequestMapping("/api/sensitive")
public class SensitiveWordController {

    /** 两次热更新之间的最小间隔：一次 reload 是全表读 + 重建 Trie，不能被当成免费接口刷 */
    private static final long RELOAD_MIN_INTERVAL_MS = 3000L;

    /** 上一次成功执行 reload 的时间戳（进程内节流，多实例下各自独立） */
    private final AtomicLong lastReloadAt = new AtomicLong(0L);

    @Autowired
    private SensitiveWordService sensitiveWordService;

    /** 检查文本是否命中敏感词：命中返回命中的词 */
    @GetMapping("/check")
    public Result<Map<String, Object>> check(@RequestParam String text) {
        String hit = sensitiveWordService.check(text);
        return Result.ok(Map.of(
                "text", text,
                "hit", hit == null ? "" : hit,
                "pass", hit == null
        ));
    }

    /** 过滤：返回把命中词替换成 ** 后的文本 */
    @GetMapping("/filter")
    public Result<Map<String, Object>> filter(@RequestParam String text) {
        // 走 service 而不是直接调 DfaSensitiveFilter：
        // 保证和 /check 用同一套归一化（小写 + 去空白），否则会出现
        // "check 说命中、filter 却替换不掉"的口径不一致。
        return Result.ok(Map.of(
                "text", text,
                "filtered", sensitiveWordService.filter(text)
        ));
    }

    /** 当前已加载的敏感词数量 */
    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.ok(sensitiveWordService.wordCount());
    }

    /**
     * 从数据库重新加载词库（热更新）。
     *
     * <p>为什么要在这里加一个节流：这个接口的代价是"全表 SELECT + 重建整棵 Trie"，
     * 而且它原来是<b>完全不需要登录</b>的 —— 一个 for 循环就能把数据库和 CPU 打满。
     * 现在有两条防线：
     * <ol>
     *   <li>{@code WebConfig} 把 {@code /api/sensitive/**} 纳入 JWT 拦截（至少要求登录）；</li>
     *   <li>这里再加一个"最少间隔 {@value #RELOAD_MIN_INTERVAL_MS} ms"的进程内节流。</li>
     * </ol>
     */
    @PostMapping("/reload")
    public Result<String> reload(@RequestAttribute(value = "username", required = false) String username) {
        long now = System.currentTimeMillis();
        long last = lastReloadAt.get();
        if (now - last < RELOAD_MIN_INTERVAL_MS) {
            return Result.fail("刷新太频繁，请 " + (RELOAD_MIN_INTERVAL_MS / 1000) + " 秒后再试");
        }
        lastReloadAt.set(now);

        sensitiveWordService.reload();
        log.info("敏感词词库已被热更新，操作人={}，当前 {} 个词", username, sensitiveWordService.wordCount());
        return Result.ok("已重新加载 " + sensitiveWordService.wordCount() + " 个敏感词");
    }
}
