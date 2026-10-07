package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.UserLike;

/**
 * 点赞关系 Mapper。
 * MyBatis-Plus 的 BaseMapper 自带 CRUD，这里用 selectList 查"该用户点过赞的所有视频id"即可。
 * 推荐系统通过它拿到用户的原始行为。
 */
public interface UserLikeMapper extends BaseMapper<UserLike> {
}
