package com.zl.d27.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d27.observability.ModelCallRecorder;
import com.zl.d27.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关闭正文访问日志后，聊天请求 TraceId 与 MODEL_CALL 终态记录仍必须可用。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.access-log.enabled=false")
class TraceIdWithoutAccessLogTest extends AbstractPersistenceCleanupTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> modelCallAppender;

    private Logger modelCallLogger;

    @BeforeEach
    void attachModelCallAppender() {
        modelCallLogger = (Logger) LoggerFactory.getLogger(ModelCallRecorder.LOGGER_NAME);
        modelCallAppender = new ListAppender<>();
        modelCallAppender.start();
        modelCallLogger.addAppender(modelCallAppender);
    }

    @AfterEach
    void detachModelCallAppender() {
        modelCallLogger.detachAppender(modelCallAppender);
    }

    @Test
    void traceAndModelRecordShouldSurviveAccessLoggingDisable() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Dev-User", "ut_trace_without_access_log");
        headers.set("X-Request-Id", "disabled-log-trace-001");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/ai/chat",
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("message", "关闭访问日志后的 Trace 测试")), headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":200");
        assertThat(response.getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("disabled-log-trace-001");
        assertThat(response.getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo("disabled-log-trace-001");

        JsonNode modelCall = modelCallAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(this::parseQuietly)
                .filter(node -> node != null && "MODEL_CALL".equals(node.path("event").asText()))
                .filter(node -> "disabled-log-trace-001".equals(node.path("traceId").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(modelCall.path("status").asText()).isEqualTo("SUCCESS");
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }
}
