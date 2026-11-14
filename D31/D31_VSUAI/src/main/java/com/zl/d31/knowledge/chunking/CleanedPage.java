package com.zl.d31.knowledge.chunking;

/**
 * Page-level original and cleaned text retained for traceability.
 */
public record CleanedPage(
        Integer pageNumber,
        String originalText,
        String cleanedText,
        int originalCharacterCount,
        int cleanedCharacterCount,
        int paragraphCount) {
}
