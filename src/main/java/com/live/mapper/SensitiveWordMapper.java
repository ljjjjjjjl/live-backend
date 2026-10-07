package com.live.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.live.entity.SensitiveWord;

/**
 * 敏感词 Mapper。
 * 继承 BaseMapper 后自带 selectList/selectAll 等；敏感词表只需"全量加载"，
 * 所以用 selectList(null) 拿全部即可，无需自定义 SQL。
 */
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
