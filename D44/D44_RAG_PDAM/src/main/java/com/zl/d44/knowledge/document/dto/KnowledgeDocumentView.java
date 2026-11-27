package com.zl.d44.knowledge.document.dto;

import java.time.LocalDateTime;

/**
 * 文档登记/导入结果，不包含原始文件内容和完整解析正文。
 */
public record KnowledgeDocumentView(
        String documentId,
        String source,
        String title,
        String version,
        String status,
        LocalDateTime updatedAt,
        String fileName,
        String contentType,
        Long fileSize,
        String contentHash,
        Integer parsedChars,
        Integer pageCount,
        String errorCode,
        String errorMessage,
        boolean duplicate,
        // 追加：权限范围三键（无标签为 null）
        String tenantId,
        String deptId,
        String deviceId) {
}
