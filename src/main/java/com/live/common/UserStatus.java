package com.live.common;

/**
 * 账号状态常量。
 *
 * <p>对应数据库列 {@code user.status}（{@code varchar(20)}，非空，默认 {@code NORMAL}）。
 * 用字符串而不是枚举，是为了让 MyBatis-Plus 直接映射、并且后续加状态不需要改表结构。
 *
 * <p><b>安全约定</b>：
 * <ul>
 *   <li>状态**只能由服务端**变更（注册/建号时强制写 {@link #NORMAL}），
 *       客户端入参走 DTO，DTO 里没有 status 字段（见 {@code RegisterRequest}）。</li>
 *   <li>登录一律调用 {@link #canLogin(String)} 判定，
 *       **只有 NORMAL 能登录**；其余状态（含未知值）一律拒绝 —— 白名单而不是黑名单，
 *       避免以后新增状态时忘了加拦截。</li>
 * </ul>
 */
public final class UserStatus {

    /** 正常：唯一允许登录的状态 */
    public static final String NORMAL = "NORMAL";

    /** 封禁：违规封号 */
    public static final String BANNED = "BANNED";

    /** 注销：用户主动注销 */
    public static final String DELETED = "DELETED";

    /** 停用：管理员停用/风控 */
    public static final String DISABLED = "DISABLED";

    /** 锁定：多次失败/风控临时锁定 */
    public static final String LOCKED = "LOCKED";

    private UserStatus() {
    }

    /**
     * 该状态是否允许登录（白名单：只有 {@link #NORMAL} 放行）。
     *
     * @param status 数据库里的状态值；历史数据可能为 null（等同于 NORMAL）
     * @return true 表示允许登录
     */
    public static boolean canLogin(String status) {
        // 兼容历史数据：迁移脚本已把老行填成 NORMAL，这里对 null 也放行
        if (status == null || status.trim().isEmpty()) {
            return true;
        }
        return NORMAL.equalsIgnoreCase(status.trim());
    }
}
