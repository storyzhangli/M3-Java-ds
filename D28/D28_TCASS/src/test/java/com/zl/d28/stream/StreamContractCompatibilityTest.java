package com.zl.d28.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d28.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步契约稳定、同步/流式共享 Mock 内容，以及流式接口会话归属边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamContractCompatibilityTest extends AbstractPersistenceCleanupTest {

    private static final String SYNC_URL = "/api/v1/ai/chat";
    private static final String STREAM_URL = SYNC_URL + "/stream";
    private static final String OWNER_HEADER = "X-Dev-User";

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void plainSyncContractAndMockContentShouldRemainCompatibleWithStream() throws Exception {
        ResponseEntity<String> sync = restTemplate.exchange(SYNC_URL, HttpMethod.POST,
                jsonEntity(Map.of("message", "同步流式一致性"), "ut_stream_contract"),
                String.class);
        assertThat(sync.getStatusCode().value()).isEqualTo(200);
        JsonNode syncBody = objectMapper.readTree(sync.getBody());
        assertThat(syncBody.path("code").asInt()).isEqualTo(200);
        assertThat(syncBody.path("data").path("mode").asText()).isEqualTo("mock");
        assertThat(syncBody.path("data").path("mocked").asBoolean()).isTrue();
        assertThat(syncBody.path("data").path("sessionId").asText()).hasSize(32);
        assertThat(syncBody.path("data").path("content").asText()).isNotBlank();

        ResponseEntity<byte[]> streamed = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of("message", "同步流式一致性"), "ut_stream_contract"),
                byte[].class);
        assertThat(streamed.getHeaders().getContentType().toString()).contains("text/event-stream");
        String body = new String(streamed.getBody(), StandardCharsets.UTF_8);
        String joined = body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .filter(line -> line.contains("\"content\""))
                .map(this::extractContentSafely)
                .reduce("", String::concat);
        // 普通同步和普通流式均走同一个 Mock 文案来源，历史为空时文本应一致。
        assertThat(joined).isEqualTo(syncBody.path("data").path("content").asText());
    }

    @Test
    void streamShouldRejectAnotherOwnerBeforeAnySseFrame() throws Exception {
        ResponseEntity<byte[]> first = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of("message", "建立流式会话"), "ut_stream_owner_a"),
                byte[].class);
        String sessionId = firstSessionId(new String(first.getBody(), StandardCharsets.UTF_8));

        ResponseEntity<String> denied = restTemplate.exchange(STREAM_URL, HttpMethod.POST,
                jsonEntity(Map.of(
                        "message", "试图读取他人会话",
                        "sessionId", sessionId), "ut_stream_owner_b"),
                String.class);

        JsonNode error = objectMapper.readTree(denied.getBody());
        assertThat(error.path("code").asInt()).isEqualTo(4001);
        assertThat(error.path("msg").asText()).contains("无权访问");
        assertThat(denied.getBody()).doesNotContain("event:start");
    }

    private String firstSessionId(String sseBody) throws Exception {
        for (String block : sseBody.split("\\r?\\n\\r?\\n")) {
            if (!block.contains("event:start")) {
                continue;
            }
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    return objectMapper.readTree(line.substring("data:".length()).trim())
                            .path("sessionId").asText();
                }
            }
        }
        throw new AssertionError("未找到 start 事件");
    }

    private String extractContentSafely(String dataLine) {
        try {
            JsonNode node = objectMapper.readTree(dataLine);
            return node.has("content") ? node.path("content").asText() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private HttpEntity<String> jsonEntity(Map<String, Object> payload, String owner) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(OWNER_HEADER, owner);
        return new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
    }
}
