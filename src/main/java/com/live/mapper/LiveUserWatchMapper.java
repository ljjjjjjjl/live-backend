package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.LiveUserWatch;

/**
 * 用户观看直播间记录 Mapper。
 * BaseMapper 自带 CRUD，这里用 selectList 查"该用户看过哪些直播间"。
 */
public interface LiveUserWatchMapper extends BaseMapper<LiveUserWatch> {
}
