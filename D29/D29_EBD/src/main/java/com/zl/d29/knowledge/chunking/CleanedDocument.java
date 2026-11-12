package com.zl.d29.knowledge.chunking;

import com.zl.d29.knowledge.chunking.CleanedPage;
import com.zl.d29.knowledge.chunking.CleanedParagraph;

import java.util.List;

/**
 * Normalized document text plus page and paragraph provenance.
 */
public record CleanedDocument(
        String documentId,
        String title,
        String version,
        String normalizedText,
        int originalCharacterCount,
        int cleanedCharacterCount,
        List<CleanedPage> pages,
        List<CleanedParagraph> paragraphs) {

    public CleanedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        normalizedText = normalizedText == null ? "" : normalizedText;
    }
}
