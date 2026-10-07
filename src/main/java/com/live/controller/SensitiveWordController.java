package com.live.controller;

import com.live.common.DfaSensitiveFilter;
import com.live.common.Result;
import com.live.service.SensitiveWordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
 */
@RestController
@RequestMapping("/api/sensitive")
public class SensitiveWordController {

    @Autowired
    private SensitiveWordService sensitiveWordService;

    @Autowired
    private DfaSensitiveFilter dfaSensitiveFilter;

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
        return Result.ok(Map.of(
                "text", text,
                "filtered", dfaSensitiveFilter.filter(text)
        ));
    }

    /** 当前已加载的敏感词数量 */
    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.ok(sensitiveWordService.wordCount());
    }

    /** 从数据库重新加载词库（热更新） */
    @PostMapping("/reload")
    public Result<String> reload() {
        sensitiveWordService.reload();
        return Result.ok("已重新加载 " + sensitiveWordService.wordCount() + " 个敏感词");
    }
}
