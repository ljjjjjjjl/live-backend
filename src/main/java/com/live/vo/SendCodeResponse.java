package com.live.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 发送验证码的响应体。
 *
 * <h3>为什么单独建一个 DTO，而不是往 Map 里塞 key</h3>
 * 旧实现返回 {@code Map<String,String>} 并往里 {@code put("phone", 完整手机号)}、
 * 条件性 {@code put("mockCode", 验证码)}。问题有两个：
 * <ol>
 *   <li><b>Map 没有契约</b>：字段是"运行时决定有没有"的，很容易在某个分支里多塞一个敏感字段，
 *       code review 时也看不出来；</li>
 *   <li><b>无法从结构上保证不泄露</b>：{@code mockCode} 一旦被打开就是一个明文验证码。</li>
 * </ol>
 * 现在：
 * <ul>
 *   <li>手机号**只返回脱敏值** {@code phoneMasked}（永远不含完整手机号）；</li>
 *   <li>{@code mockCode} 用 {@link JsonInclude.Include#NON_NULL} 标注 —— 只有开发环境显式回显时才有值，
 *       其余情况下**这个字段不会出现在 JSON 里**（不是 null，而是根本不存在）。</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SendCodeResponse {

    /** 脱敏后的手机号，如 138****5678 */
    private String phoneMasked;

    /** 验证码有效期（秒），方便前端倒计时对齐服务端 */
    private Integer expireSeconds;

    /** 仅 dev/test 且 app.sms.expose-mock-code=true 时才有值；生产环境该字段不存在 */
    private String mockCode;

    public SendCodeResponse(String phoneMasked, Integer expireSeconds) {
        this.phoneMasked = phoneMasked;
        this.expireSeconds = expireSeconds;
    }
}
