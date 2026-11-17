package com.zl.d34.rag.model;

/**
 * RAG 证据决策稳定原因码（八类，与 HTTP 层 {@code ResultCode} 体系并存，不另建体系）。
 */
public enum RagReasonCode {

    /** 知识库无任何检索结果。 */
    NO_EVIDENCE("知识库无任何检索结果"),

    /** 仅关键词相似，证据不能支持问题。 */
    LOW_RELEVANCE("仅关键词相似，证据不能支持问题"),

    /** 有相关证据但覆盖度不足。 */
    INSUFFICIENT_EVIDENCE("有相关证据但覆盖度不足"),

    /** 缺设备型号/故障代码等必要条件，需澄清。 */
    MISSING_REQUIRED_CONTEXT("缺少必要条件，需要澄清"),

    /** 有效证据对同一事实给出矛盾内容。 */
    EVIDENCE_CONFLICT("有效证据对同一事实给出矛盾内容"),

    /** 引用不在本次来源白名单或格式非法。 */
    INVALID_CITATION("引用不在本次来源白名单或格式非法"),

    /** 输出含无证据支持的设备参数/故障码/维修步骤。 */
    UNSUPPORTED_CLAIM("输出含无证据支持的设备参数或故障码"),

    /** 生成后校验未通过的受控失败。 */
    GENERATION_VALIDATION_FAILED("生成后校验未通过的受控失败");

    private final String description;

    RagReasonCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
