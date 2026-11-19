package com.zl.d36.rag.model;

/**
 * RAG 证据决策稳定原因码（十类，与 HTTP 层 {@code ResultCode} 体系并存，不另建体系）。
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
    GENERATION_VALIDATION_FAILED("生成后校验未通过的受控失败"),

    /** 引用指向的文档/Chunk 在检索后、响应前被删除或失效（删除竞态）。 */
    CITATION_TARGET_DELETED("引用指向的文档或片段已删除或失效"),

    /** 答案中出现无来源 URL、本机路径或危险协议（模型/用户拼接）。 */
    FABRICATED_URL("答案中出现无来源或危险协议的链接或本机路径");

    private final String description;

    RagReasonCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
