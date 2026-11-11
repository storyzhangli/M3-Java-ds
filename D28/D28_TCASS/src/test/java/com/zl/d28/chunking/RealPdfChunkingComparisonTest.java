package com.zl.d28.chunking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d28.knowledge.chunking.ChunkBoundaryType;
import com.zl.d28.knowledge.chunking.ChunkSourceDocument;
import com.zl.d28.knowledge.chunking.ChunkingComparison;
import com.zl.d28.knowledge.chunking.ChunkingProperties;
import com.zl.d28.knowledge.chunking.ChunkingResult;
import com.zl.d28.knowledge.chunking.DocumentChunk;
import com.zl.d28.knowledge.chunking.DocumentChunker;
import com.zl.d28.knowledge.chunking.DocumentPageText;
import com.zl.d28.knowledge.chunking.TextCleaner;
import com.zl.d28.knowledge.document.KnowledgeDocumentParser;
import com.zl.d28.knowledge.document.KnowledgeImportProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RealPdfChunkingComparisonTest {

    private static final Path SOURCE = Path.of(
            "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\proj_docs",
            "D28-佳能用户手册-DB-测试素材.pdf");

    @Test
    void sameRealPdfShouldProduceTraceableFixedAndStructureAwareChunks() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SOURCE), "configured real PDF is unavailable");
        byte[] bytes = Files.readAllBytes(SOURCE);
        KnowledgeDocumentParser parser =
                new KnowledgeDocumentParser(new KnowledgeImportProperties());
        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(
                new MockMultipartFile(
                        "file",
                        SOURCE.getFileName().toString(),
                        "application/pdf",
                        bytes)));
        String documentId = "D28-CANON-MP500-REAL";
        String title = "Canon PIXMA MP500 All-In-One User Manual";
        String version = "metadata-2026-10-24";
        ChunkSourceDocument source = new ChunkSourceDocument(
                documentId,
                title,
                version,
                parsed.pages().stream()
                        .map(page -> new DocumentPageText(page.pageNumber(), page.content()))
                        .toList());
        ChunkingProperties properties = new ChunkingProperties();
        TextCleaner cleaner = new TextCleaner();
        DocumentChunker chunker = new DocumentChunker(cleaner, properties);
        var cleaned = cleaner.clean(source);
        ChunkingComparison comparison = chunker.compare(source);

        assertThat(parsed.pages()).hasSize(11);
        assertThat(cleaned.cleanedCharacterCount()).isGreaterThan(8_000);
        assertThat(cleaned.paragraphs().size()).isGreaterThan(10);
        assertTwoStrategies(
                comparison.fixedLength(), properties, cleaned.normalizedText());
        assertTwoStrategies(
                comparison.structureAware(), properties, cleaned.normalizedText());

        Set<Integer> allFixedPages = new HashSet<>();
        comparison.fixedLength().chunks().forEach(chunk ->
                allFixedPages.addAll(chunk.pageNumbers()));
        assertThat(allFixedPages).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 11).boxed().toList());
        for (int index = 1; index < comparison.fixedLength().chunks().size(); index++) {
            String tail = codePointTail(
                    comparison.fixedLength().chunks().get(index - 1).content(),
                    properties.getOverlap());
            assertThat(comparison.fixedLength().chunks().get(index).content())
                    .startsWith(tail);
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("sourceType", "local-real-pdf");
        root.put("source", SOURCE.toAbsolutePath().toString());
        root.put("fileName", SOURCE.getFileName().toString());
        root.put("sha256", parser.inspect(new MockMultipartFile(
                "file", SOURCE.getFileName().toString(), "application/pdf", bytes)).contentHash());
        root.put("documentId", documentId);
        root.put("title", title);
        root.put("version", version);
        root.put("parserName", parsed.parserName());
        root.put("parserVersion", parsed.parserVersion());
        root.put("pageCount", parsed.pages().size());
        root.put("originalCharacterCount", cleaned.originalCharacterCount());
        root.put("cleanedCharacterCount", cleaned.cleanedCharacterCount());
        root.put("paragraphCount", cleaned.paragraphs().size());
        root.put("lengthUnit", "UNICODE_CODE_POINTS");
        root.put("note", "Character counts are not model token counts.");
        ArrayNode cleaningPages = root.putArray("cleaningPages");
        for (var page : cleaned.pages()) {
            ObjectNode item = cleaningPages.addObject();
            item.put("pageNumber", page.pageNumber());
            item.put("originalCharacterCount", page.originalCharacterCount());
            item.put("cleanedCharacterCount", page.cleanedCharacterCount());
            item.put("paragraphCount", page.paragraphCount());
            item.put("originalPreview", preview(page.originalText(), 500));
            item.put("cleanedPreview", preview(page.cleanedText(), 500));
        }
        root.set("fixedLength", resultNode(mapper, comparison.fixedLength()));
        root.set("structureAware", resultNode(mapper, comparison.structureAware()));
        root.put("fixedSemanticBoundaryChunks",
                semanticBoundaryCount(comparison.fixedLength()));
        root.put("structureSemanticBoundaryChunks",
                semanticBoundaryCount(comparison.structureAware()));

        Path moduleRoot = Path.of("").toAbsolutePath();
        Path evidence = moduleRoot.getParent().getParent()
                .resolve("docs/evidence/D28/D28-chunking-comparison.json");
        Files.createDirectories(evidence.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), root);
    }

    private void assertTwoStrategies(ChunkingResult result,
                                     ChunkingProperties properties,
                                     String normalizedText) {
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.sourceCharacterCount()).isGreaterThan(8_000);
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.characterCount()).isBetween(1, properties.getMaxChunkSize());
            assertThat(chunk.documentId()).isEqualTo("D28-CANON-MP500-REAL");
            assertThat(chunk.title()).isEqualTo("Canon PIXMA MP500 All-In-One User Manual");
            assertThat(chunk.version()).isEqualTo("metadata-2026-10-24");
            assertThat(chunk.sources()).isNotEmpty();
        });
        for (int index = 0; index < result.chunks().size(); index++) {
            assertThat(result.chunks().get(index).chunkIndex()).isEqualTo(index + 1);
        }
        assertThat(normalizedText).startsWith(result.chunks().get(0).content());
        assertThat(normalizedText).endsWith(
                result.chunks().get(result.chunks().size() - 1).content());
        for (int index = 1; index < result.chunks().size(); index++) {
            String previousTail = codePointTail(
                    result.chunks().get(index - 1).content(), properties.getOverlap());
            assertThat(result.chunks().get(index).content()).startsWith(previousTail);
        }
    }

    private long semanticBoundaryCount(ChunkingResult result) {
        return result.metrics().boundaryCounts().getOrDefault(ChunkBoundaryType.PARAGRAPH, 0L)
                + result.metrics().boundaryCounts().getOrDefault(ChunkBoundaryType.DOCUMENT_END, 0L);
    }

    private ObjectNode resultNode(ObjectMapper mapper, ChunkingResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("strategy", result.strategy().name());
        node.put("chunkSize", result.chunkSize());
        node.put("overlap", result.overlap());
        node.put("minChunkSize", result.minChunkSize());
        node.put("maxChunkSize", result.maxChunkSize());
        node.put("chunkCount", result.metrics().chunkCount());
        node.put("averageCharacterCount", result.metrics().averageCharacterCount());
        node.put("minimumCharacterCount", result.metrics().minimumCharacterCount());
        node.put("maximumCharacterCount", result.metrics().maximumCharacterCount());
        node.set("boundaryCounts", mapper.valueToTree(result.metrics().boundaryCounts()));
        node.set("lengthDistribution", mapper.valueToTree(result.metrics().lengthDistribution()));
        ArrayNode chunks = node.putArray("chunks");
        for (DocumentChunk chunk : result.chunks()) {
            ObjectNode item = chunks.addObject();
            item.put("chunkIndex", chunk.chunkIndex());
            item.put("characterCount", chunk.characterCount());
            item.put("content", chunk.content());
            item.put("endBoundary", chunk.endBoundary().name());
            item.set("pageNumbers", mapper.valueToTree(chunk.pageNumbers()));
            item.set("sources", mapper.valueToTree(chunk.sources()));
        }
        return node;
    }

    private String codePointTail(String value, int count) {
        int total = value.codePointCount(0, value.length());
        int start = value.offsetByCodePoints(0, total - Math.min(total, count));
        return value.substring(start);
    }

    private String preview(String value, int maximumCodePoints) {
        int total = value.codePointCount(0, value.length());
        int end = value.offsetByCodePoints(0, Math.min(total, maximumCodePoints));
        return value.substring(0, end);
    }
}
