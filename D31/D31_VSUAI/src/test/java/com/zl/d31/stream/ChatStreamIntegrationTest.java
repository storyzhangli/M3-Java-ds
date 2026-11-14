package com.zl.d31.stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d31.observability.ModelCallRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Tomcat（随机端口）下的 SSE 流式接口集成测试，默认 mock 模式，无需外网与密钥。
 * 验证响应头、SSE 帧顺序、UTF-8 中文、结束事件、参数错误的 JSON 回落以及静态页面可访问。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamIntegrationTest {

    private static final String STREAM_URL = "/api/v1/ai/chat";

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
    void streamShouldReturnOrderedSseFramesWithUtf8ContentAndCompleteEvent() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", "stream-trace-001");
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "SSE流式联调测试")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL + "/stream", HttpMethod.POST, entity, byte[].class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("stream-trace-001");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("stream-trace-001");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        List<SseFrame> frames = parseFrames(body);

        List<String> eventNames = frames.stream().map(SseFrame::name).toList();
        assertThat(eventNames).startsWith("start");
        assertThat(eventNames).contains("delta");
        assertThat(eventNames).endsWith("complete");
        assertThat(eventNames).doesNotContain("error");
        JsonNode start = frames.stream().filter(f -> "start".equals(f.name))
                .findFirst().orElseThrow().data;
        assertThat(start.path("requestId").asText()).isNotBlank();
        assertThat(start.path("requestId").asText()).isEqualTo("stream-trace-001");
        assertThat(start.path("sessionId").asText()).hasSize(32);

        // delta 的 seq 必须从 0 连续递增，保证前端可按序追加
        List<Integer> seqs = frames.stream()
                .filter(f -> "delta".equals(f.name))
                .map(f -> f.data.path("seq").asInt())
                .toList();
        assertThat(seqs).isNotEmpty();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i);
        }

        String joinedDelta = frames.stream()
                .filter(f -> "delta".equals(f.name))
                .map(f -> f.data.path("content").asText())
                .reduce("", String::concat);
        // UTF-8 中文正确、模拟标记存在，且完成事件统计长度与实际增量一致
        assertThat(joinedDelta).contains("[MOCK:D01]").contains("SSE流式联调测试");
        JsonNode complete = frames.stream().filter(f -> "complete".equals(f.name)).findFirst().orElseThrow().data;
        assertThat(complete.path("totalChars").asInt()).isEqualTo(joinedDelta.length());
        assertThat(complete.path("mocked").asBoolean()).isTrue();

        JsonNode modelCall = modelCallAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(this::parseQuietly)
                .filter(node -> node != null && "MODEL_CALL".equals(node.path("event").asText()))
                .filter(node -> "stream-trace-001".equals(node.path("traceId").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(modelCall.path("callMode").asText()).isEqualTo("STREAM");
        assertThat(modelCall.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(modelCall.path("usageStatus").asText()).isEqualTo("NOT_SUPPORTED");
        assertThat(modelCall.has("promptTokens")).isFalse();
    }

    @Test
    void blankMessageShouldFallBackToJsonValidationErrorInsteadOfSse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "   ")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL + "/stream", HttpMethod.POST, entity, byte[].class);

        // 流尚未开始，走全局异常处理器，返回统一 JSON 而非 event-stream
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        JsonNode json = objectMapper.readTree(new String(response.getBody(), StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt()).isEqualTo(4001);
    }

    @Test
    void indexPageShouldBeServedAsStaticResource() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/", byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String html = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(html).contains("D31 模型调用异常、超时与有限重试演示")
                .contains("/js/app.js").contains("/css/app.css");
    }

    /** 极简 SSE 帧解析：按空行分块，提取 event 名称与 JSON data。 */
    private List<SseFrame> parseFrames(String body) throws Exception {
        List<SseFrame> frames = new ArrayList<>();
        String[] blocks = body.split("\\r?\\n\\r?\\n");
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            String name = "message";
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    String part = line.substring("data:".length());
                    data.append(part.startsWith(" ") ? part.substring(1) : part);
                }
            }
            if (data.length() > 0) {
                frames.add(new SseFrame(name, objectMapper.readTree(data.toString())));
            }
        }
        return frames;
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }

    private record SseFrame(String name, JsonNode data) {
    }
}
