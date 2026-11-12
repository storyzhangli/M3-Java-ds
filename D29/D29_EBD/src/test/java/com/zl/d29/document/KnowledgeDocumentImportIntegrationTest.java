package com.zl.d29.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d29.knowledge.document.KnowledgeDocumentException;
import com.zl.d29.knowledge.document.KnowledgeDocumentService;
import com.zl.d29.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d29.support.AbstractPersistenceCleanupTest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/schema-knowledge-document.sql")
class KnowledgeDocumentImportIntegrationTest extends AbstractPersistenceCleanupTest {

    private static final String BASE_URL = "/api/v1/knowledge/documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> createdDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanupKnowledgeDocuments() {
        for (String id : createdDocumentIds) {
            jdbcTemplate.update("DELETE FROM knowledge_document_page WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document_content WHERE document_id = ?", id);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE document_id = ?", id);
        }
    }

    @Test
    void registrationShouldCreatePendingMetadataOnly() throws Exception {
        JsonNode data = register("ut_doc_register", "设备维修手册", "1.0");

        assertThat(data.path("documentId").asText()).hasSize(32);
        assertThat(data.path("source").asText()).isEqualTo("ut_doc_register");
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("contentHash").isNull()).isTrue();

        String documentId = data.path("documentId").asText();
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_document WHERE document_id = ?",
                String.class, documentId);
        Integer contentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document_content WHERE document_id = ?",
                Integer.class, documentId);
        assertThat(status).isEqualTo("PENDING");
        assertThat(contentRows).isZero();
    }

    @Test
    void registrationValidationShouldRejectMissingTitle() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", "ut_doc_invalid",
                                "version", "1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("title")));
    }

    @Test
    void validMarkdownShouldBeStoredParsedAndCompletedWithUpdatedTimestamp() throws Exception {
        JsonNode registration = register("ut_doc_valid", "冷却系统巡检", "1.0");
        String documentId = registration.path("documentId").asText();
        LocalDateTime registeredAt = LocalDateTime.parse(registration.path("updatedAt").asText());
        byte[] content = "# 巡检\n先检查冷却液液位。".getBytes(StandardCharsets.UTF_8);
        Thread.sleep(20);

        MvcResult result = mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file("guide.md", "text/markdown", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.contentHash").value(sha256(content)))
                .andExpect(jsonPath("$.data.parsedChars").value(
                        new String(content, StandardCharsets.UTF_8).strip().length()))
                .andReturn();
        JsonNode data = body(result).path("data");
        assertThat(data.path("contentType").asText()).isNotBlank();

        String parsed = jdbcTemplate.queryForObject("""
                SELECT parsed_text FROM knowledge_document_content WHERE document_id = ?
                """, String.class, documentId);
        byte[] stored = jdbcTemplate.queryForObject("""
                SELECT content FROM knowledge_document_content WHERE document_id = ?
                """, byte[].class, documentId);
        LocalDateTime completedAt = jdbcTemplate.queryForObject("""
                SELECT updated_at FROM knowledge_document WHERE document_id = ?
                """, LocalDateTime.class, documentId);

        assertThat(parsed).isEqualTo(new String(content, StandardCharsets.UTF_8).strip());
        assertThat(stored).isEqualTo(content);
        assertThat(completedAt).isAfter(registeredAt);

        mockMvc.perform(get(BASE_URL + "/{id}", documentId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void emptyFileShouldFailDocumentWithStableCode() throws Exception {
        String documentId = register("ut_doc_empty", "空文件", "1").path("documentId").asText();
        LocalDateTime before = documentUpdatedAt(documentId);
        Thread.sleep(20);

        mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file("empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4101));

        assertThat(documentStatus(documentId)).isEqualTo("FAILED");
        assertThat(documentErrorCode(documentId)).isEqualTo("DOCUMENT_FILE_INVALID");
        assertThat(documentUpdatedAt(documentId)).isAfter(before);
    }

    @Test
    void extensionSpoofingAndInvalidEncodingShouldFailAfterContentValidation() throws Exception {
        String extensionId = register("ut_doc_extension", "不支持扩展名", "1")
                .path("documentId").asText();
        mockMvc.perform(multipart(BASE_URL + "/{id}/import", extensionId)
                        .file(file("program.exe", "application/octet-stream",
                                new byte[]{'M', 'Z', 0, 1})))
                .andExpect(jsonPath("$.code").value(4101));
        assertThat(documentStatus(extensionId)).isEqualTo("FAILED");

        String binaryId = register("ut_doc_binary", "伪装文本", "1")
                .path("documentId").asText();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 1, 2};
        mockMvc.perform(multipart(BASE_URL + "/{id}/import", binaryId)
                        .file(file("fake.txt", "text/plain", png)))
                .andExpect(jsonPath("$.code").value(4101));
        assertThat(documentStatus(binaryId)).isEqualTo("FAILED");

        String encodingId = register("ut_doc_encoding", "非法编码", "1")
                .path("documentId").asText();
        byte[] invalidUtf8 = {(byte) 0xc3, 0x28, (byte) 0xff};
        mockMvc.perform(multipart(BASE_URL + "/{id}/import", encodingId)
                        .file(file("bad.txt", "text/plain", invalidUtf8)))
                .andExpect(jsonPath("$.code").value(4101));
        assertThat(documentStatus(encodingId)).isEqualTo("FAILED");
        assertThat(documentErrorMessage(encodingId)).contains("UTF-8");
    }

    @Test
    void parserFailureShouldTransitionFromProcessingToFailed() throws Exception {
        String documentId = register("ut_doc_parse", "无正文HTML", "1")
                .path("documentId").asText();
        byte[] blankHtml = "<html><head></head><body>   </body></html>"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file("blank.html", "text/html", blankHtml)))
                .andExpect(jsonPath("$.code").value(4221));

        assertThat(documentStatus(documentId)).isEqualTo("FAILED");
        assertThat(documentErrorCode(documentId)).isEqualTo("DOCUMENT_PARSE_FAILED");
    }

    @Test
    void processingStateShouldRejectConcurrentReimportAndCompleteExplicitly() throws Exception {
        String documentId = register("ut_doc_state", "状态流转", "1")
                .path("documentId").asText();
        byte[] content = "状态流转内容".getBytes(StandardCharsets.UTF_8);
        LocalDateTime now = LocalDateTime.now();
        documentRepository.claimForProcessing(
                documentId, "state.txt", "text/plain", content.length,
                sha256(content), content, now);

        assertThat(documentStatus(documentId)).isEqualTo("PROCESSING");
        mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file("again.txt", "text/plain", content)))
                .andExpect(jsonPath("$.code").value(4091));
        assertThat(documentStatus(documentId)).isEqualTo("PROCESSING");

        documentRepository.complete(documentId, "text/plain", "解析文本",
                List.of(), "UTF-8 text", "built-in", LocalDateTime.now());
        assertThat(documentStatus(documentId)).isEqualTo("COMPLETED");
    }

    @Test
    void pdfImportShouldPersistPageBoundariesAndExposeSourceMetadata() throws Exception {
        JsonNode registration = register("ut_doc_pdf_pages", "PDF 页面定位手册", "2.1");
        String documentId = registration.path("documentId").asText();
        byte[] pdf = pdfWithPages("First page safety", "", "Third page troubleshooting");

        mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file("manual.pdf", "application/pdf", pdf)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.pageCount").value(3))
                .andExpect(jsonPath("$.data.parserName").doesNotExist());

        String aggregatePdfText = jdbcTemplate.queryForObject("""
                SELECT parsed_text FROM knowledge_document_content WHERE document_id = ?
                """, String.class, documentId);
        Integer pageRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document_page WHERE document_id = ?
                """, Integer.class, documentId);
        assertThat(aggregatePdfText).isNull();
        assertThat(pageRows).isEqualTo(3);

        mockMvc.perform(get(BASE_URL + "/{id}/pages", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.source").value("ut_doc_pdf_pages"))
                .andExpect(jsonPath("$.data.title").value("PDF 页面定位手册"))
                .andExpect(jsonPath("$.data.version").value("2.1"))
                .andExpect(jsonPath("$.data.pageCount").value(3))
                .andExpect(jsonPath("$.data.pages[0].pageNumber").value(1))
                .andExpect(jsonPath("$.data.pages[0].content").value(
                        org.hamcrest.Matchers.containsString("First page safety")))
                .andExpect(jsonPath("$.data.pages[1].pageNumber").value(2))
                .andExpect(jsonPath("$.data.pages[1].blankPage").value(true))
                .andExpect(jsonPath("$.data.pages[1].content").value(""))
                .andExpect(jsonPath("$.data.pages[2].pageNumber").value(3))
                .andExpect(jsonPath("$.data.pages[2].content").value(
                        org.hamcrest.Matchers.containsString("Third page troubleshooting")))
                .andExpect(jsonPath("$.data.pages[2].parserName").value("Apache PDFBox"))
                .andExpect(jsonPath("$.data.pages[2].parserVersion").value("3.0.7"));
    }

    @Test
    void pagesEndpointShouldRejectDocumentBeforeParsingCompletes() throws Exception {
        String documentId = register("ut_doc_pdf_pending", "尚未导入", "1")
                .path("documentId").asText();

        mockMvc.perform(get(BASE_URL + "/{id}/pages", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4091))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("尚未完成解析")));
    }

    @Test
    void chunksEndpointShouldRejectDocumentBeforeParsingCompletes() throws Exception {
        String documentId = register("ut_doc_chunk_pending", "尚未切分", "1")
                .path("documentId").asText();

        mockMvc.perform(get(BASE_URL + "/{id}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4091))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("尚未完成解析")));
    }

    @Test
    void realCanonPdfShouldPreserveAllElevenPhysicalPages() throws Exception {
        Path source = Path.of(
                "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\proj_docs",
                "D28-佳能用户手册-DB-测试素材.pdf");
        Assumptions.assumeTrue(Files.isRegularFile(source), "configured real PDF is unavailable");
        String documentId = register(
                "D28-佳能用户手册-DB-测试素材.pdf",
                "Canon PIXMA MP500 User Manual",
                "metadata-2026-10-24")
                .path("documentId").asText();
        byte[] pdf = Files.readAllBytes(source);

        MvcResult result = mockMvc.perform(multipart(BASE_URL + "/{id}/import", documentId)
                        .file(file(source.getFileName().toString(), "application/pdf", pdf)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageCount").value(11))
                .andReturn();
        assertThat(body(result).path("data").path("parsedChars").asInt()).isGreaterThan(8_000);

        MvcResult pagesResult = mockMvc.perform(get(BASE_URL + "/{id}/pages", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pageCount").value(11))
                .andReturn();
        JsonNode data = body(pagesResult).path("data");
        JsonNode pages = data.path("pages");
        assertThat(pages).hasSize(11);
        for (int index = 0; index < pages.size(); index++) {
            assertThat(pages.get(index).path("pageNumber").asInt()).isEqualTo(index + 1);
        }
        assertThat(pages.get(0).path("content").asText())
                .contains("Canon PIXMA MP500", "INTRODUCTION", "SAFETY INFORMATION");
        assertThat(pages.get(1).path("content").asText())
                .contains("Figure 3.1", "SETUP", "Unpacking");
        assertThat(pages.get(9).path("content").asText())
                .contains("Cannot scan from", "Error message on");
        assertThat(pages.get(10).path("content").asText()).contains("Privacy details");
        assertThat(data.path("source").asText())
                .isEqualTo("D28-佳能用户手册-DB-测试素材.pdf");
        assertThat(data.path("pages").get(0).path("parserVersion").asText())
                .isEqualTo("3.0.7");

        mockMvc.perform(get(BASE_URL + "/{id}/chunks", documentId)
                        .queryParam("strategy", "STRUCTURE_AWARE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value(documentId))
                .andExpect(jsonPath("$.data.title").value("Canon PIXMA MP500 User Manual"))
                .andExpect(jsonPath("$.data.version").value("metadata-2026-10-24"))
                .andExpect(jsonPath("$.data.strategy").value("STRUCTURE_AWARE"))
                .andExpect(jsonPath("$.data.metrics.chunkCount").isNumber())
                .andExpect(jsonPath("$.data.chunks[0].chunkIndex").value(1))
                .andExpect(jsonPath("$.data.chunks[0].sources").isArray())
                .andExpect(jsonPath("$.data.chunks[0].pageNumbers").isArray());

        mockMvc.perform(get(BASE_URL + "/{id}/chunks/compare", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fixedLength.strategy").value("FIXED_LENGTH"))
                .andExpect(jsonPath("$.data.structureAware.strategy").value("STRUCTURE_AWARE"))
                .andExpect(jsonPath("$.data.fixedLength.documentId").value(documentId))
                .andExpect(jsonPath("$.data.structureAware.documentId").value(documentId));
    }

    @Test
    void duplicateImportShouldKeepOriginalAndNotProcessSecondDocument() throws Exception {
        String firstId = register("ut_doc_dup_first", "第一份", "1").path("documentId").asText();
        String secondId = register("ut_doc_dup_second", "第二份", "2").path("documentId").asText();
        byte[] content = "同一份设备维修资料".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart(BASE_URL + "/{id}/import", firstId)
                        .file(file("one.txt", "text/plain", content)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        LocalDateTime firstUpdatedAt = documentUpdatedAt(firstId);
        String firstVersion = documentVersion(firstId);
        Thread.sleep(20);

        mockMvc.perform(multipart(BASE_URL + "/{id}/import", secondId)
                        .file(file("two.txt", "text/plain", content)))
                .andExpect(jsonPath("$.code").value(4092));

        assertThat(documentStatus(secondId)).isEqualTo("PENDING");
        assertThat(documentStatus(firstId)).isEqualTo("COMPLETED");
        assertThat(documentVersion(firstId)).isEqualTo(firstVersion);
        assertThat(documentUpdatedAt(firstId)).isEqualTo(firstUpdatedAt);

        Thread.sleep(20);
        mockMvc.perform(multipart(BASE_URL + "/{id}/import", firstId)
                        .file(file("one-again.txt", "text/plain", content)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(documentUpdatedAt(firstId)).isEqualTo(firstUpdatedAt);
    }

    @Test
    void concurrentDuplicateImportsShouldHaveOnlyOneStoredHash() throws Exception {
        String firstId = register("ut_doc_race_a", "并发一", "1").path("documentId").asText();
        String secondId = register("ut_doc_race_b", "并发二", "1").path("documentId").asText();
        byte[] content = "并发导入相同内容".getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> {
                start.await();
                try {
                    return documentService.importFile(
                            firstId, file("a.txt", "text/plain", content)).status();
                } catch (KnowledgeDocumentException ex) {
                    return ex.getResultCode().name();
                }
            });
            Future<String> second = pool.submit(() -> {
                start.await();
                try {
                    return documentService.importFile(
                            secondId, file("b.txt", "text/plain", content)).status();
                } catch (KnowledgeDocumentException ex) {
                    return ex.getResultCode().name();
                }
            });
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .contains("COMPLETED", "DOCUMENT_DUPLICATE");
        } finally {
            pool.shutdownNow();
        }

        Integer storedHashes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document
                WHERE content_hash = ?
                """, Integer.class, sha256(content));
        assertThat(storedHashes).isEqualTo(1);
    }

    private JsonNode register(String source, String title, String version) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", source,
                                "title", title,
                                "version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = body(result).path("data");
        createdDocumentIds.add(data.path("documentId").asText());
        return data;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private byte[] pdfWithPages(String... texts) throws Exception {
        try (PDDocument document = new PDDocument();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(font, 12);
                        content.newLineAtOffset(50, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
    }

    private String documentStatus(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_document WHERE document_id = ?", String.class, id);
    }

    private String documentVersion(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM knowledge_document WHERE document_id = ?", String.class, id);
    }

    private String documentErrorCode(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT error_code FROM knowledge_document WHERE document_id = ?", String.class, id);
    }

    private String documentErrorMessage(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT error_message FROM knowledge_document WHERE document_id = ?", String.class, id);
    }

    private LocalDateTime documentUpdatedAt(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM knowledge_document WHERE document_id = ?",
                LocalDateTime.class, id);
    }
}
