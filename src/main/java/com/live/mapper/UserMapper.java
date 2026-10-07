package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.User;

/**
 * 用户 Mapper。
 * 继承 BaseMapper 后，MyBatis-Plus 自动提供 add/delete/update/select 等 CRUD 方法，
 * 无需手写 SQL。
 */
public interface UserMapper extends BaseMapper<User> {
}
