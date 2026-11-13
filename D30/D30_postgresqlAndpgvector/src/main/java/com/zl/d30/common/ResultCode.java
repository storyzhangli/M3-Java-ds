package com.zl.d30.common;

import lombok.Getter;

/**
 * 统一业务返回码。
 *
 * <p>HTTP 层统一返回 200，调用结果通过该业务码区分，保持 {@code code/msg/data} 契约稳定。</p>
 */
@Getter
public enum ResultCode {

    /** 成功。 */
    SUCCESS(200, "success"),

    /** 请求参数校验失败（空白、超长、报文不可读等）。 */
    PARAM_INVALID(4001, "请求参数校验失败"),

    /** AI 配置/鉴权/客户端错误：缺失或占位密钥、上游 4xx（含 401）。 */
    AI_CONFIG_ERROR(5001, "AI 服务配置或鉴权异常，请检查密钥与参数配置"),

    /** AI 服务端暂不可用：上游 5xx/超时/限流且重试耗尽。 */
    AI_UNAVAILABLE(5002, "AI 服务暂不可用，请稍后重试"),

    /** 知识文档不存在。 */
    DOCUMENT_NOT_FOUND(4041, "知识文档不存在"),

    /** 当前文档状态不允许执行导入。 */
    DOCUMENT_STATE_CONFLICT(4091, "知识文档当前状态不允许导入"),

    /** 文件内容已由其他文档导入。 */
    DOCUMENT_DUPLICATE(4092, "相同内容的文档已存在"),

    /** 上传文件不满足安全或格式要求。 */
    DOCUMENT_FILE_INVALID(4101, "上传文件不合法"),

    /** 文件基础解析失败。 */
    DOCUMENT_PARSE_FAILED(4221, "文件基础解析失败"),

    /** Embedding 输入为空、纯空白或超过模型上限。 */
    EMBEDDING_INPUT_INVALID(4002, "Embedding 输入不合法"),

    /** Embedding 返回空向量、维度错误或非法数值。 */
    EMBEDDING_RESPONSE_INVALID(4222, "Embedding 响应不合法"),

    /** 目标向量索引与当前模型向量空间不兼容。 */
    VECTOR_INDEX_INCOMPATIBLE(4093, "向量索引不兼容"),

    /** 未预期的系统兜底错误。 */
    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试");

    private final Integer code;

    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
