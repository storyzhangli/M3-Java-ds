package com.zl.d31.chunking;

import com.zl.d31.knowledge.chunking.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    @Test
    void fixedChunkingWithoutOverlapShouldCoverTextWithoutGaps() {
        ChunkingProperties properties = properties(20, 0, 5, 30);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        String text = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        ChunkingResult result = chunker.chunk(
                document(text), ChunkStrategy.FIXED_LENGTH);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks()).extracting(DocumentChunk::characterCount)
                .containsExactly(20, 16);
        assertThat(result.chunks().stream()
                .map(DocumentChunk::content)
                .reduce("", String::concat)).isEqualTo(text);
        assertThat(result.chunks()).allSatisfy(chunk ->
                assertThat(chunk.characterCount()).isLessThanOrEqualTo(20));
    }

    @Test
    void positiveOverlapShouldBeExactAtCodePointBoundary() {
        ChunkingProperties properties = properties(20, 5, 6, 30);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

        ChunkingResult result = chunker.chunk(
                document(text), ChunkStrategy.FIXED_LENGTH);

        assertThat(result.chunks().size()).isGreaterThan(1);
        for (int index = 1; index < result.chunks().size(); index++) {
            String previousTail = codePointTail(
                    result.chunks().get(index - 1).content(), 5);
            assertThat(result.chunks().get(index).content()).startsWith(previousTail);
        }
    }

    @Test
    void structureStrategyShouldMergeShortParagraphsAndKeepBoundaries() {
        ChunkingProperties properties = properties(40, 0, 8, 80);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        String text = "TITLE\n\nFirst short paragraph.\n\nSecond short paragraph.\n\nThird.";

        ChunkingResult result = chunker.chunk(
                document(text), ChunkStrategy.STRUCTURE_AWARE);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).sources()).hasSize(4);
        assertThat(result.chunks().get(0).endBoundary())
                .isEqualTo(ChunkBoundaryType.DOCUMENT_END);
        assertThat(result.chunks().get(0).content())
                .contains("TITLE", "First short", "Second short", "Third.");
    }

    @Test
    void longParagraphAndLongSentenceShouldNeverExceedMaximum() {
        ChunkingProperties properties = properties(35, 0, 8, 45);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        String sentence = "This is one complete maintenance sentence. ";
        String text = "MAINTENANCE\n\n" + sentence.repeat(12);

        ChunkingResult result = chunker.chunk(
                document(text), ChunkStrategy.STRUCTURE_AWARE);

        assertThat(result.chunks().size()).isGreaterThan(1);
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.characterCount()).isLessThanOrEqualTo(45);
        });
        assertThat(result.metrics().maximumCharacterCount()).isLessThanOrEqualTo(45);
    }

    @Test
    void chunkShouldPreserveCrossPageSourcesAndDocumentIdentity() {
        ChunkingProperties properties = properties(100, 0, 10, 120);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        ChunkSourceDocument document = new ChunkSourceDocument(
                "doc-cross-page",
                "跨页手册",
                "v3",
                List.of(
                        new DocumentPageText(1, "First page setup."),
                        new DocumentPageText(2, "Second page troubleshooting.")));

        ChunkingResult result = chunker.chunk(document, ChunkStrategy.STRUCTURE_AWARE);

        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.documentId()).isEqualTo("doc-cross-page");
            assertThat(chunk.title()).isEqualTo("跨页手册");
            assertThat(chunk.version()).isEqualTo("v3");
            assertThat(chunk.chunkIndex()).isEqualTo(1);
            assertThat(chunk.pageNumbers()).containsExactly(1, 2);
            assertThat(chunk.sources()).hasSize(2);
            assertThat(chunk.sources().get(0).paragraphIndex()).isEqualTo(1);
            assertThat(chunk.sources().get(1).paragraphIndex()).isEqualTo(1);
        });
    }

    @Test
    void sameChunkIndexInDifferentDocumentVersionsMustRemainDistinct() {
        DocumentChunker chunker = new DocumentChunker(
                new TextCleaner(), properties(100, 0, 10, 120));

        DocumentChunk versionOne = chunker.chunk(
                new ChunkSourceDocument(
                        "same-id",
                        "Manual",
                        "v1",
                        List.of(new DocumentPageText(1, "Version one text."))),
                ChunkStrategy.STRUCTURE_AWARE).chunks().get(0);
        DocumentChunk versionTwo = chunker.chunk(
                new ChunkSourceDocument(
                        "same-id",
                        "Manual",
                        "v2",
                        List.of(new DocumentPageText(1, "Version two text."))),
                ChunkStrategy.STRUCTURE_AWARE).chunks().get(0);

        assertThat(versionOne.chunkIndex()).isEqualTo(1);
        assertThat(versionTwo.chunkIndex()).isEqualTo(1);
        assertThat(versionOne.version()).isEqualTo("v1");
        assertThat(versionTwo.version()).isEqualTo("v2");
        assertThat(versionOne.documentId()).isEqualTo(versionTwo.documentId());
    }

    @Test
    void extremelyShortLastSegmentShouldBeRetainedWithoutEmptyChunk() {
        ChunkingResult result = new DocumentChunker(
                new TextCleaner(), properties(10, 0, 4, 10))
                .chunk(document("0123456789X"), ChunkStrategy.FIXED_LENGTH);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(1).content()).isEqualTo("X");
        assertThat(result.chunks()).allSatisfy(chunk ->
                assertThat(chunk.content()).isNotBlank());
    }

    @Test
    void chineseAndEmojiShouldUseCodePointsAndNeverSplitSurrogates() {
        ChunkingProperties properties = properties(3, 0, 1, 3);
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties);
        String text = "A😀中文B";

        ChunkingResult result = chunker.chunk(
                document(text), ChunkStrategy.FIXED_LENGTH);

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).content()).isEqualTo("A😀中");
        assertThat(result.chunks().get(1).content()).isEqualTo("文B");
        assertThat(result.chunks().stream().map(DocumentChunk::content)
                .reduce("", String::concat)).isEqualTo(text);
        assertThat(result.chunks()).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("\uFFFD"));
    }

    @Test
    void emptyOrWhitespaceTextShouldProduceNoChunks() {
        DocumentChunker chunker = new DocumentChunker(new TextCleaner(), properties(
                20, 2, 5, 30));

        assertThat(chunker.chunk(document(" \r\n\t "), ChunkStrategy.FIXED_LENGTH)
                .chunks()).isEmpty();
        assertThat(chunker.chunk(document(""), ChunkStrategy.STRUCTURE_AWARE)
                .metrics().chunkCount()).isZero();
    }

    private ChunkSourceDocument document(String text) {
        return new ChunkSourceDocument(
                "doc-1",
                "设备手册",
                "v1",
                List.of(new DocumentPageText(1, text)));
    }

    private ChunkingProperties properties(int chunkSize, int overlap,
                                          int minimum, int maximum) {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setChunkSize(chunkSize);
        properties.setOverlap(overlap);
        properties.setMinChunkSize(minimum);
        properties.setMaxChunkSize(maximum);
        return properties;
    }

    private String codePointTail(String value, int count) {
        int total = value.codePointCount(0, value.length());
        int start = value.offsetByCodePoints(0, total - Math.min(total, count));
        return value.substring(start);
    }
}
