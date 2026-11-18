package com.zl.d35.rag.dto;

/**
 * RAG 最小闭环请求。
 *
 * @param query      用户问题（必填，非空白）
 * @param topK       覆盖默认 Top-K，null 用配置
 * @param documentId 可选检索过滤：文档标识
 * @param version    可选检索过滤：文档版本
 * @param strategy   可选检索过滤：切分策略
 */
public record RagRequest(String query,
                         Integer topK,
                         String documentId,
                         String version,
                         String strategy) {
}
