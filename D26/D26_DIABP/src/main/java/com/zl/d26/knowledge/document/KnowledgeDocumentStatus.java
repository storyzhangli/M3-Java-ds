package com.zl.d26.knowledge.document;

/**
 * 文档导入与基础解析状态。
 */
public enum KnowledgeDocumentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canStartImport() {
        return this == PENDING || this == FAILED;
    }
}
