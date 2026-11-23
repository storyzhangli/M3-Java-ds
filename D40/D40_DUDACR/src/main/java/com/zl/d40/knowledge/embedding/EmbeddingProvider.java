package com.zl.d40.knowledge.embedding;

/**
 * Embedding provider family.
 */
public enum EmbeddingProvider {
    LOCAL,
    OPENAI,
    /** 阿里云百炼（DashScope compatible-mode），two_ 隔离链路专用。 */
    BAILIAN
}
