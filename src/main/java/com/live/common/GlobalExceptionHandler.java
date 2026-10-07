package com.live.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把各种异常统一转成 {@code {code:1, msg, data:null}}，避免把
 * Spring 默认的错误堆栈/HTML 直接抛给前端。
 *
 * <p>覆盖的异常：
 * <ul>
 *   <li>{@link BusinessException} —— 业务校验失败（Service 抛的），返回它的提示语</li>
 *   <li>{@link MethodArgumentNotValidException} —— @RequestBody + @Valid 校验失败</li>
 *   <li>{@link ConstraintViolationException} —— 类上 @Validated + 方法参数约束失败（@RequestParam/@PathVariable）</li>
 *   <li>{@link BindException} —— 数据绑定失败（表单等）</li>
 *   <li>{@link MissingServletRequestParameterException} —— 缺少必填参数</li>
 *   <li>{@link MethodArgumentTypeMismatchException} —— 参数类型不匹配（传给 Long 的是字符串等）</li>
 *   <li>{@link HttpMessageNotReadableException} —— 请求体 JSON 解析失败</li>
 *   <li>{@link HttpRequestMethodNotSupportedException} —— 请求方法不支持（用错 GET/POST）</li>
 *   <li>{@link NoResourceFoundException} —— 路径/静态资源不存在</li>
 *   <li>{@link Exception} —— 兜底，未预期异常记日志并返回统一提示，不泄露堆栈</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：返回它自带的业务提示 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getMessage());
    }

    /** @RequestBody + @Valid 校验失败：取第一个字段错误提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgNotValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : "参数校验失败";
        return Result.fail(msg);
    }

    /** 方法参数校验失败（@RequestParam/@PathVariable，类上需 @Validated） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("参数校验失败");
        return Result.fail(msg);
    }

    /** 数据绑定失败（表单/对象绑定） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        FieldError fe = e.getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : "参数绑定失败";
        return Result.fail(msg);
    }

    /** 缺少必填参数（没传、或 required=true 却没值） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail("缺少参数: " + e.getParameterName());
    }

    /** 参数类型不匹配（如给 Long 传了不可转的字符串） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail("参数格式错误: " + e.getName());
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return Result.fail("请求体格式错误");
    }

    /**
     * 请求方法不支持（如用 GET 打了只支持 POST 的接口）。
     * 不加这个 handler 会落到下面的兜底 Exception，返回"系统繁忙"且刷 ERROR 日志，
     * 排错时很误导——所以单独处理，明确告诉调用方"方法用错了"。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail("请求方法不支持：" + e.getMethod() + "，请改用 " + e.getSupportedHttpMethods());
    }

    /**
     * 静态资源 / 接口路径不存在（Spring 6.1 的 NoResourceFoundException）。
     * 单独处理成"路径不存在"，避免被兜底成"系统繁忙"（例如访问 / 但欢迎页未注册时）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail("路径不存在：" + e.getResourcePath());
    }

    /**
     * 唯一约束冲突（如 username 唯一索引 uk_username、手机号唯一索引 uk_phone）。
     *
     * <p>典型的并发场景：两个请求同时注册同一用户名/手机号，应用层"先查再插"都查不到，
     * 最终由数据库唯一索引拦下 —— 这时会抛 DuplicateKeyException。
     * 转成友好业务提示，避免被兜底成"系统繁忙"。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.fail("数据已存在（用户名或手机号已被占用）");
    }

    /** 兜底：未预期异常 → 记录日志 + 返回统一提示，不把堆栈抛给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail("系统繁忙，请稍后再试");
    }
}
