package com.zl.d30.knowledge.vectorstore;

/**
 * pgvector 相似度索引类型（D30 仅实际支持 HNSW + vector_cosine_ops）。
 */
public enum PgVectorIndexType {
    NONE,
    IVFFLAT,
    HNSW
}
