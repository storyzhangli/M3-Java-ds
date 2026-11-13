package com.zl.d30.knowledge.chunking;

/**
 * A paragraph inside normalized document text with page and code-point offsets.
 */
public record CleanedParagraph(
        Integer pageNumber,
        int paragraphIndex,
        int startCodePoint,
        int endCodePoint,
        String text,
        boolean heading) {
}
