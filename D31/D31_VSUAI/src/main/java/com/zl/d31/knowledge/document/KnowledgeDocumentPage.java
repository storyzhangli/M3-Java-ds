package com.zl.d31.knowledge.document;

import java.time.LocalDateTime;

/**
 * 已持久化的页面级提取结果。
 */
public record KnowledgeDocumentPage(
        String documentId,
        int pageNumber,
        String content,
        int characterCount,
        boolean blankPage,
        String parserName,
        String parserVersion,
        LocalDateTime updatedAt) {
}
