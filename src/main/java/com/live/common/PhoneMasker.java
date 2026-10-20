package com.live.common;

/**
 * 手机号脱敏工具。
 *
 * <p><b>为什么需要它</b>：手机号属于个人敏感信息（PII）。它一旦进入
 * 用户名、URL、日志、接口响应，就会顺着日志采集/前端展示/浏览器历史泄出去。
 * 本项目约定：**任何对外可见的位置都不出现完整手机号**——
 * <ul>
 *   <li>日志：{@code log.info("... {}", PhoneMasker.mask(phone))}</li>
 *   <li>接口响应：只返回 {@link #mask(String)} 后的值</li>
 *   <li>用户名/昵称：**不能**由手机号派生（见 {@code PhoneLoginService#createUserByPhone}）</li>
 * </ul>
 *
 * <p>数据库里的 {@code user.phone} 是唯一的"明文存储点"（业务需要按它查询/去重），
 * 这一列需要靠库级权限与备份加密保护，属于残余风险，已在交付说明里列出。
 */
public final class PhoneMasker {

    private PhoneMasker() {
    }

    /**
     * 脱敏：保留前 3 位与后 4 位，中间用 4 个 * 代替（{@code 13812345678 -> 138****5678}）。
     *
     * <p>对不合法的输入（null / 太短）做兜底，**绝不原样返回**，避免"以为脱敏了其实没脱"。
     *
     * @param phone 原始手机号
     * @return 脱敏后的字符串；输入为 null/空时返回空串
     */
    public static String mask(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        if (phone.length() < 7) {
            // 太短无法安全保留前后缀：整体打码，避免泄露
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
