package com.live.common;

/**
 * 校验分组标记。
 *
 * <p>同一实体在不同接口下校验规则不同，用分组区分：
 * <ul>
 *   <li>默认组（Default）：登录/注册都一定要校验的（如 username/password 非空、长度）。</li>
 *   <li>{@link Create} 组：注册时额外校验的（如昵称 name 必填）；登录时不校验 name。</li>
 * </ul>
 * <pre>
 *   register -> @Validated({Create.class, Default.class})
 *   login    -> @Valid（默认组）
 * </pre>
 */
public interface ValidationGroups {

    interface Create {
    }
}
