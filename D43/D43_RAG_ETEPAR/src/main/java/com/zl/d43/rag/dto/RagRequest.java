package com.zl.d43.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * RAG 最小闭环请求（入参经 Bean Validation 校验，Controller 侧 @Valid 触发）。
 *
 * @param query      用户问题（必填，非空白）
 * @param topK       覆盖默认 Top-K，null 用配置（1..1000）
 * @param documentId 可选检索过滤：文档标识
 * @param version    可选检索过滤：文档版本
 * @param strategy   可选检索过滤：切分策略
 */
public record RagRequest(@NotBlank(message = "查询内容不能为空") String query,
                         @Min(value = 1, message = "topK 不能小于 1")
                         @Max(value = 1000, message = "topK 不能大于 1000") Integer topK,
                         String documentId,
                         String version,
                         String strategy) {
}
