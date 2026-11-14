package com.zl.d31.knowledge.vectorstore;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 向量库检索命中结果：剥离 Spring AI Document 内部结构，仅暴露溯源与评分字段。
 */
public record VectorSearchHit(
        String id,
        String documentId,
        Integer chunkIndex,
        String title,
        Double score,
        String contentSnippet) {

    private static final int DEFAULT_SNIPPET_LENGTH = 160;

    public static VectorSearchHit from(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new VectorSearchHit(
                document.getId(),
                asString(metadata.get("documentId")),
                asInteger(metadata.get("chunkIndex")),
                asString(metadata.get("title")),
                document.getScore(),
                snippet(document.getText(), DEFAULT_SNIPPET_LENGTH));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String snippet(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String compact = text.strip();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }
}
