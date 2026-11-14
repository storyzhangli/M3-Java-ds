package com.zl.d31.stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d31.observability.ModelCallRecorder;
import com.zl.d31.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 真实 HTTP 客户端主动关闭输入流：验证服务端取消上游并把未完成轮次收口，
 * 不允许留下 PENDING 或写成 COMPLETED。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamClientDisconnectIntegrationTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_stream_disconnect";

    @LocalServerPort
    private int port;

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
    void clientCloseShouldCancelStreamAndNeverPersistCompletedTurn() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat/stream"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Dev-User", OWNER)
                .header("X-Request-Id", "disconnect-trace-001")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("message", "客户端断流取消测试"))))
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        String sessionId;
        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            sessionId = readSessionIdAfterFirstDelta(reader);
            // start 和首个 delta 已先后到达，随后立即关闭连接；Mock 流仍在继续输出，
            // 既证明客户端可在 complete 前收到增量，也能触发服务端取消路径。
        }
        assertThat(sessionId).hasSize(32);

        await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject("""
                    SELECT status FROM chat_message
                    WHERE session_id = ? AND role = 'user'
                    ORDER BY sequence_no DESC LIMIT 1
                    """, String.class, sessionId);
            assertThat(status).isIn("FAILED", "INTERRUPTED");
            Integer completedAssistant = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM chat_message
                    WHERE session_id = ? AND role = 'assistant' AND status = 'COMPLETED'
                    """, Integer.class, sessionId);
            assertThat(completedAssistant).isZero();

            JsonNode modelCall = modelCallAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .map(this::parseQuietly)
                    .filter(node -> node != null && "MODEL_CALL".equals(node.path("event").asText()))
                    .filter(node -> "disconnect-trace-001".equals(node.path("traceId").asText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(modelCall.path("status").asText()).isEqualTo("CANCELLED");
        });
    }

    private String readSessionIdAfterFirstDelta(BufferedReader reader) throws Exception {
        String sessionId = null;
        String eventName = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:") && "start".equals(eventName)) {
                JsonNode data = objectMapper.readTree(line.substring("data:".length()).trim());
                sessionId = data.path("sessionId").asText();
                eventName = null;
            } else if (line.startsWith("data:") && "delta".equals(eventName)) {
                eventName = null;
                if (sessionId != null) {
                    return sessionId;
                }
            }
        }
        throw new AssertionError("连接在首个 delta 前结束");
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }
}
