package com.zl.d30.knowledge.chunking;

/**
 * Raw text and optional physical page number used as chunking input.
 */
public record DocumentPageText(Integer pageNumber, String text) {

    public DocumentPageText {
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must start at 1");
        }
        text = text == null ? "" : text;
    }
}
