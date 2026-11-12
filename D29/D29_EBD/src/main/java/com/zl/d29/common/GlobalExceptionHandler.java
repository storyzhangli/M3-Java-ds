package com.zl.d29.common;

import com.zl.d29.common.AiConfigurationException;
import com.zl.d29.common.Result;
import com.zl.d29.common.ResultCode;
import com.zl.d29.knowledge.document.KnowledgeDocumentException;
import com.zl.d29.model.ModelFailure;
import com.zl.d29.model.ModelFailureClassifier;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;

/**
 * 全局异常处理器。
 *
 * <p>将各类异常归一为统一 {@link Result} 结构（HTTP 状态保持 200，业务结果通过 code 区分）。
 * 对外响应<b>只包含固定/安全提示，绝不回显异常堆栈、上游原始报文、请求头或 API Key</b>；
 * 详细诊断信息仅写入服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ModelFailureClassifier failureClassifier;

    public GlobalExceptionHandler(ModelFailureClassifier failureClassifier) {
        this.failureClassifier = failureClassifier;
    }

    /** 知识文档业务错误：使用稳定业务码和已清洗的用户提示。 */
    @ExceptionHandler(KnowledgeDocumentException.class)
    public Result<Void> handleKnowledgeDocument(KnowledgeDocumentException ex) {
        log.warn("知识文档处理失败，code={}，异常类型={}",
                ex.getResultCode().name(), ex.getClass().getSimpleName());
        return Result.fail(ex.getResultCode().getCode(), ex.getMessage());
    }

    /** Servlet Multipart 在进入 Controller 前拒绝超限请求。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过容器限制，异常类型={}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.DOCUMENT_FILE_INVALID.getCode(),
                "上传文件超过服务端大小限制");
    }

    /** Multipart 帧、请求解析等容器级错误，不向客户端回显内部信息。 */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException ex) {
        log.warn("Multipart 请求处理失败，异常类型={}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.DOCUMENT_FILE_INVALID.getCode(),
                "上传文件请求格式不合法");
    }

    /**
     * Bean Validation 注解校验失败（@NotBlank / @Size 等）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_INVALID.getMsg());
        log.warn("参数校验失败: {}", detail);
        return Result.fail(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /**
     * 约束违反、请求体不可读/缺失、以及业务层主动抛出的非法参数（如可配置长度上限）。
     */
    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public Result<Void> handleBadRequest(Exception ex) {
        String detail;
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null) {
            detail = ex.getMessage();
        } else if (ex instanceof ConstraintViolationException && ex.getMessage() != null) {
            detail = ex.getMessage();
        } else {
            detail = "请求体缺失或格式非法，需要 JSON 报文且包含 message 字段";
        }
        log.warn("请求参数异常: {}", detail);
        return Result.fail(ResultCode.PARAM_INVALID.getCode(), detail);
    }

    /**
     * 数据库完整性冲突（如 template_id 唯一约束在并发创建时兜底）。
     * 对外只给稳定业务提示，SQL 状态与约束名仅写服务端日志，不回显数据库细节。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据库完整性约束冲突，异常类型: {}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.PARAM_INVALID.getCode(), "数据冲突或唯一约束校验失败，请检查业务标识是否重复");
    }

    /**
     * 真实模式下密钥缺失/占位等配置问题，调用前拦截，未产生外部请求。
     */
    @ExceptionHandler(AiConfigurationException.class)
    public Result<Void> handleAiConfiguration(AiConfigurationException ex) {
        ModelFailure failure = failureClassifier.classify(ex);
        log.error("AI 配置校验失败，category={}", failure.category());
        return Result.fail(failureClassifier.resultCode(ex));
    }

    /**
     * Spring AI 不可重试异常：上游 4xx（含 401 鉴权失败、参数错误）。
     * 仅记录异常类型，不记录可能含上游报文的 message，更不记录请求头。
     */
    @ExceptionHandler(NonTransientAiException.class)
    public Result<Void> handleNonTransient(NonTransientAiException ex) {
        ModelFailure failure = failureClassifier.classify(ex);
        log.error("AI 客户端/鉴权异常，category={}，status={}",
                failure.category(), failure.httpStatus());
        return Result.fail(failureClassifier.resultCode(ex));
    }

    /**
     * Spring AI 可重试异常：上游 5xx/超时/限流且重试耗尽。
     */
    @ExceptionHandler(TransientAiException.class)
    public Result<Void> handleTransient(TransientAiException ex) {
        ModelFailure failure = failureClassifier.classify(ex);
        log.error("AI 服务端临时异常且重试结束，category={}，status={}",
                failure.category(), failure.httpStatus());
        return Result.fail(failureClassifier.resultCode(ex));
    }

    /**
     * RestClient 网络层异常由统一分类器判断是建连、安全读取超时还是其他 I/O。
     */
    @ExceptionHandler(ResourceAccessException.class)
    public Result<Void> handleResourceAccess(ResourceAccessException ex) {
        ModelFailure failure = failureClassifier.classify(ex);
        log.error("AI HTTP 调用异常，category={}", failure.category());
        return Result.fail(failureClassifier.resultCode(ex));
    }

    /**
     * SSE 客户端断开后，Servlet 输出流写失败会以 IOException 进入异常分发。
     * 响应已提交时不能再次序列化统一 JSON；静默收尾避免把正常断流误报为系统异常。
     */
    @ExceptionHandler(IOException.class)
    public Object handleServletIo(IOException ex, HttpServletResponse response) {
        log.info("Servlet 响应流已关闭，停止写出，异常类型={}", ex.getClass().getSimpleName());
        return response.isCommitted() ? null : Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /**
     * 兜底异常：完整堆栈仅写入服务端日志，对外返回通用提示。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception ex) {
        ModelFailure failure = failureClassifier.classify(ex);
        log.error("未预期的系统异常，category={}", failure.category(), ex);
        return Result.fail(failureClassifier.resultCode(ex));
    }
}
