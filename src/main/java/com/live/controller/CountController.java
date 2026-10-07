package com.live.controller;

import com.live.common.Result;
import com.live.service.CountCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 互动计数接口（Redis INCR 计数缓存，第 3 周）。
 *
 * <ul>
 *   <li>POST /api/count/{targetId}/{type}         —— type in like/favorite/forward，计数 +1</li>
 *   <li>GET  /api/count/{targetId}                —— 读目标的所有计数（like/favorite/forward）</li>
 * </ul>
 *
 * <p>说明：真实项目这里点赞/收藏要先判重（同一用户同一目标只算一次，用 Redis Set），
 * 且计数是"展示值"，最终靠异步落库 + 对账和数据库一致。这里提供 Redis 计数 + 读取能力演示。
 */
@RestController
@RequestMapping("/api/count")
public class CountController {

    @Autowired
    private CountCacheService countCacheService;

    /** 计数 +1：/api/count/1/like、/api/count/1/favorite、/api/count/1/forward */
    @PostMapping("/{targetId}/{type}")
    public Result<Long> incr(@PathVariable Long targetId, @PathVariable String type) {
        if (!isValidType(type)) {
            return Result.fail("type 仅支持 like/favorite/forward");
        }
        countCacheService.incr(targetId, type);
        return Result.ok(countCacheService.getCount(targetId, type));
    }

    /** 读目标的所有计数 */
    @GetMapping("/{targetId}")
    public Result<Map<Object, Object>> all(@PathVariable Long targetId) {
        return Result.ok(countCacheService.getAll(targetId));
    }

    private boolean isValidType(String type) {
        return CountCacheService.LIKE.equals(type)
                || CountCacheService.FAVORITE.equals(type)
                || CountCacheService.FORWARD.equals(type);
    }
}
