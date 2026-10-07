package com.live.common;

import lombok.Data;

/**
 * 统一返回结构体：所有接口都返回 {code, msg, data}
 */
@Data
public class Result<T> {

    private Integer code; // 0=成功 1=失败
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(String msg) {
        Result<T> r = new Result<>();
        r.code = 1;
        r.msg = msg;
        return r;
    }
}
