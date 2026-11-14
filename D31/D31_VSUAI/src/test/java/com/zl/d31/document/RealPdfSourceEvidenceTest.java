package com.zl.d31.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d31.knowledge.document.KnowledgeDocumentParser;
import com.zl.d31.knowledge.document.KnowledgeImportProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses the configured real Canon PDF to produce reproducible Java parser evidence.
 */
class RealPdfSourceEvidenceTest {

    private static final Path SOURCE = Path.of(
            "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\proj_docs",
            "D28-佳能用户手册-DB-测试素材.pdf");

    @Test
    void javaParserShouldExtractAllElevenRealPdfPages() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SOURCE), "configured real PDF is unavailable");
        byte[] bytes = Files.readAllBytes(SOURCE);
        KnowledgeDocumentParser parser =
                new KnowledgeDocumentParser(new KnowledgeImportProperties());
        KnowledgeDocumentParser.InspectedFile inspected = parser.inspect(
                new MockMultipartFile(
                        "file",
                        SOURCE.getFileName().toString(),
                        "application/pdf",
                        bytes));
        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(inspected);

        assertThat(parsed.pages()).hasSize(11);
        assertThat(parsed.parserVersion()).isEqualTo("3.0.7");
        assertThat(parsed.pages()).allSatisfy(page ->
                assertThat(page.pageNumber()).isBetween(1, 11));
        assertThat(parsed.pages().get(0).content())
                .contains("Canon PIXMA MP500", "INTRODUCTION", "SAFETY INFORMATION");
        assertThat(parsed.pages().get(1).content())
                .contains("Figure 3.1", "SETUP", "Unpacking");
        assertThat(parsed.pages().get(9).content())
                .contains("Cannot scan from", "Error message on");
        assertThat(parsed.pages().get(10).content()).contains("Privacy details");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("sourceType", "local-real-pdf");
        root.put("source", SOURCE.toAbsolutePath().toString());
        root.put("fileName", SOURCE.getFileName().toString());
        root.put("title", "Canon PIXMA MP500 All-In-One Photo Printer, Copier, and Scanner User Manual");
        root.put("version", "PDF metadata CreationDate D:20261024080126Z");
        root.put("sha256", inspected.contentHash());
        root.put("fileSize", inspected.size());
        root.put("parserName", parsed.parserName());
        root.put("parserVersion", parsed.parserVersion());
        root.put("pageCount", parsed.pages().size());
        root.put("status", "COMPLETED");
        ArrayNode pages = root.putArray("pages");
        for (var page : parsed.pages()) {
            ObjectNode item = pages.addObject();
            item.put("documentId", "verified-by-import-integration-test");
            item.put("physicalPageNumber", page.pageNumber());
            item.put("content", page.content());
            item.put("characterCount", page.characterCount());
            item.put("blankPage", page.blankPage());
        }
        ObjectNode checks = root.putObject("sourceChecks");
        checks.put("page1", "Cover, title, introduction, safety information and package contents.");
        checks.put("page2", "Figure 3.1 and numbered setup procedure.");
        checks.put("page10", "Troubleshooting table text.");
        checks.put("page11", "Manuals.plus footer and question block.");
        checks.put("limitations",
                "Text extraction does not reconstruct images, table borders, columns or OCR.");

        Path moduleRoot = Path.of("").toAbsolutePath();
        Path evidence = moduleRoot.getParent().getParent()
                .resolve("docs/evidence/D28/D28-java-pdf-extraction.json");
        Files.createDirectories(evidence.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), root);
    }
}
