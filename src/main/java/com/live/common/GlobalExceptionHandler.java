package com.live.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
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
 * <h3>HTTP 状态码（本轮修复）</h3>
 * <p>原来所有 handler 都返回 HTTP 200 —— 响应体里 {@code code:1}，但传输层是"成功"。
 * 后果是：网关/APM/nginx access log 里看不到任何错误率，4xx 和 5xx 全部混成 200，
 * 出故障时只能去扒应用日志。现在按语义设置状态码：
 * <table border="1">
 *   <tr><th>异常</th><th>HTTP</th></tr>
 *   <tr><td>{@link BusinessException}（业务校验失败）</td><td>200（保留：它是"正常的业务结果"，不是传输层错误）</td></tr>
 *   <tr><td>参数校验 / 缺参 / 类型不匹配</td><td>400</td></tr>
 *   <tr><td>请求方法不支持</td><td>405</td></tr>
 *   <tr><td>Content-Type 不支持</td><td>415</td></tr>
 *   <tr><td>路径/静态资源不存在</td><td>404</td></tr>
 *   <tr><td>唯一键 / 数据完整性冲突</td><td>409</td></tr>
 *   <tr><td>未预期异常</td><td>500</td></tr>
 * </table>
 * <p>响应体结构保持不变（{@code {code,msg,data}}），所以前端 {@code if (data.code !== 0)} 的判断逻辑完全不用改。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：返回它自带的业务提示（HTTP 200：这是"业务上不成立"，不是服务出错） */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getMessage());
    }

    /** @RequestBody + @Valid 校验失败：取第一个字段错误提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgNotValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : "参数校验失败";
        return of(HttpStatus.BAD_REQUEST, msg);
    }

    /** 方法参数校验失败（@RequestParam/@PathVariable，类上需 @Validated） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("参数校验失败");
        return of(HttpStatus.BAD_REQUEST, msg);
    }

    /** 数据绑定失败（表单/对象绑定） */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        FieldError fe = e.getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : "参数绑定失败";
        return of(HttpStatus.BAD_REQUEST, msg);
    }

    /** 缺少必填参数（没传、或 required=true 却没值） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return of(HttpStatus.BAD_REQUEST, "缺少参数: " + e.getParameterName());
    }

    /** 参数类型不匹配（如给 Long 传了不可转的字符串） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return of(HttpStatus.BAD_REQUEST, "参数格式错误: " + e.getName());
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return of(HttpStatus.BAD_REQUEST, "请求体格式错误");
    }

    /** Content-Type 不支持（比如把表单当 JSON 发） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "请求类型不支持：" + e.getContentType() + "，请改用 application/json");
    }

    /**
     * 请求方法不支持（如用 GET 打了只支持 POST 的接口）。
     * 不加这个 handler 会落到下面的兜底 Exception，返回"系统繁忙"且刷 ERROR 日志，
     * 排错时很误导——所以单独处理，明确告诉调用方"方法用错了"。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return of(HttpStatus.METHOD_NOT_ALLOWED,
                "请求方法不支持：" + e.getMethod() + "，请改用 " + e.getSupportedHttpMethods());
    }

    /**
     * 静态资源 / 接口路径不存在（Spring 6.1 的 NoResourceFoundException）。
     * 单独处理成"路径不存在"，避免被兜底成"系统繁忙"。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return of(HttpStatus.NOT_FOUND, "路径不存在：" + e.getResourcePath());
    }

    /**
     * 唯一约束冲突（如 username 唯一索引 uk_username、手机号唯一索引 uk_phone）。
     *
     * <p>典型的并发场景：两个请求同时注册同一用户名/手机号，应用层"先查再插"都查不到，
     * 最终由数据库唯一索引拦下 —— 这时会抛 DuplicateKeyException。
     * 转成友好业务提示，避免被兜底成"系统繁忙"。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return of(HttpStatus.CONFLICT, "数据已存在（用户名或手机号已被占用）");
    }

    /**
     * 更宽的数据完整性冲突兜底（{@link DuplicateKeyException} 是它的子类，已被上面的 handler 精确处理，
     * 这里覆盖的是"非唯一键"的完整性错误：NOT NULL、外键、字段超长等）。
     *
     * <p>为什么必须单独处理：这类异常同样多是**并发/脏数据**引起，如果落到 {@link #handleOther}
     * 就会返回"系统繁忙，请稍后再试"，调用方以为是服务故障、重试也永远失败；
     * 这里明确告诉调用方"数据冲突"，并且**不把数据库原始报错抛给前端**（含表名/索引名等内部信息）。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性冲突: {}", e.getMessage());
        return of(HttpStatus.CONFLICT, "数据冲突，请检查后重试");
    }

    /** 兜底：未预期异常 → 记录日志 + 返回统一提示，不把堆栈抛给前端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return of(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");
    }

    /** 统一构造"带 HTTP 状态码 + 统一响应体"的返回 */
    private ResponseEntity<Result<Void>> of(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Result.fail(msg));
    }
}
