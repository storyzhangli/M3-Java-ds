package com.zl.d30.knowledge.chunking;

import com.zl.d30.knowledge.chunking.DocumentPageText;

import java.util.List;

/**
 * Immutable document identity and source pages supplied to the chunker.
 */
public record ChunkSourceDocument(
        String documentId,
        String title,
        String version,
        List<DocumentPageText> pages) {

    public ChunkSourceDocument {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        title = title == null ? "" : title;
        version = version == null ? "" : version;
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
