package com.zl.d41.knowledge.retrieval.dto;

/**
 * 检索请求体。
 *
 * <p>所有字段合法性在 {@code SimilarityRetrievalService} 内统一校验（空查询、长度上限、
 * Top-K 范围、过滤值白名单），避免只依赖注解校验而绕过 Service 直接调用。</p>
 *
 * @param query      用户问题原文
 * @param topK       Top-K；null 时使用配置默认值 5
 * @param documentId 可选：按文档精确过滤（D31 写入的真实 metadata 字段）
 * @param version    可选：按文档版本精确过滤
 * @param strategy   可选：按切分策略精确过滤
 */
public record RetrievalQueryRequest(String query,
                                    Integer topK,
                                    String documentId,
                                    String version,
                                    String strategy) {
}
