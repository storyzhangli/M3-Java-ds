package com.zl.d29.knowledge.document.dto;

import com.zl.d29.knowledge.document.dto.KnowledgeDocumentPageView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档及其页面级提取结果。
 */
public record KnowledgeDocumentExtractionView(
        String documentId,
        String source,
        String title,
        String version,
        String contentType,
        String status,
        int pageCount,
        LocalDateTime updatedAt,
        List<KnowledgeDocumentPageView> pages) {
}
