package com.zl.d30.knowledge.document.dto;

import java.time.LocalDateTime;

/**
 * 单个物理页面的提取文本及来源定位。
 */
public record KnowledgeDocumentPageView(
        int pageNumber,
        String content,
        int characterCount,
        boolean blankPage,
        String parserName,
        String parserVersion,
        LocalDateTime updatedAt) {
}
