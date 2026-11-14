package com.zl.d31.knowledge.document.dto;

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
        boolean duplicate) {
}
