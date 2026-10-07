package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.Danmaku;

/**
 * 弹幕 Mapper。
 * 继承 BaseMapper 后，MyBatis-Plus 自动提供 insert/selectPage 等 CRUD 方法。
 * 弹幕历史查询用分页（room_id + 按 id 倒序），配合 (room_id, id) 索引走覆盖/最左匹配。
 */
public interface DanmakuMapper extends BaseMapper<Danmaku> {
}
