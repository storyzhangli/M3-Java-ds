package com.zl.d30.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d30.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "spring.servlet.multipart.max-file-size=16B",
        "spring.servlet.multipart.max-request-size=1KB",
        "app.knowledge.import.max-file-size=1MB"
})
@Sql(scripts = "/db/schema-knowledge-document.sql")
class KnowledgeDocumentContainerSizeLimitTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private TestRestTemplate restTemplate;

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
    void servletMultipartLimitShouldRejectBeforeBusinessProcessing() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String registration = restTemplate.postForObject(
                "/api/v1/knowledge/documents",
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of(
                        "source", "ut_doc_container_size",
                        "title", "容器大小限制",
                        "version", "1")), headers),
                String.class);
        JsonNode data = objectMapper.readTree(registration).path("data");
        documentId = data.path("documentId").asText();

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(
                "x".repeat(17).getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "large.txt";
            }
        });
        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/knowledge/documents/" + documentId + "/import",
                new HttpEntity<>(parts, multipartHeaders),
                String.class);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body.path("code").asInt()).isEqualTo(4101);
    }
}
