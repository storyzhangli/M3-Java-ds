package com.zl.d30.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d30.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.knowledge.import.max-file-size=32B",
        "spring.servlet.multipart.max-file-size=1MB",
        "spring.servlet.multipart.max-request-size=2MB"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/schema-knowledge-document.sql")
class KnowledgeDocumentBusinessSizeLimitTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String documentId;

    @AfterEach
    void cleanupDocument() {
        if (documentId != null) {
            jdbcTemplate.update("DELETE FROM knowledge_document_content WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE document_id = ?", documentId);
        }
    }

    @Test
    void businessLimitShouldRejectOversizeAndMarkFailed() throws Exception {
        String response = mockMvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", "ut_doc_size_business",
                                "title", "大小限制",
                                "version", "1"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        documentId = data.path("documentId").asText();

        mockMvc.perform(multipart("/api/v1/knowledge/documents/{id}/import", documentId)
                        .file(new MockMultipartFile(
                                "file", "large.txt", "text/plain",
                                "x".repeat(33).getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.code").value(4101))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("大小限制")));

        String statusValue = jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_document WHERE document_id = ?",
                String.class, documentId);
        assertThat(statusValue).isEqualTo("FAILED");
    }
}
