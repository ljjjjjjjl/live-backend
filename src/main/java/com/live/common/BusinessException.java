package com.live.common;

/**
 * 业务异常：Service 里业务规则不满足时抛出，由 {@link GlobalExceptionHandler} 统一转成
 * {@code {code:1, msg:业务提示, data:null}}。
 *
 * <p>好处：把"业务校验失败"从"到处 return Result.fail(...)"收敛成"抛异常 + 全局兜底"，
 * 业务方法签名不用反复写 Result，代码更干净、更符合"统一异常处理"的面试点。
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 1;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
