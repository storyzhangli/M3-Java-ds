package com.zl.d30.knowledge.vectorstore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * pgvector 环境只读检查报告：连通性、扩展、向量表、维度与索引的真实状态。
 */
public record PgVectorEnvironmentReport(
        boolean reachable,
        String pgVersion,
        String vectorExtensionVersion,
        boolean tableExists,
        String tableQualifiedName,
        String embeddingColumnType,
        Integer embeddingColumnDimensions,
        List<String> indexDefinitions,
        List<String> problems,
        LocalDateTime checkedAt) {

    public boolean healthy() {
        return reachable && tableExists && problems.isEmpty();
    }
}
