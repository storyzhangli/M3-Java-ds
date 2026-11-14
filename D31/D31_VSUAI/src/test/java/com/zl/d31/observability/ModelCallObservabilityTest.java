package com.zl.d31.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d31.config.AiProperties;
import com.zl.d31.logging.TraceIdFilter;
import com.zl.d31.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCallObservabilityTest {

    private static final String TRACE_ID = "trace-observability-001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;

    private Logger modelCallLogger;

    @BeforeEach
    void setUp() {
        modelCallLogger = (Logger) LoggerFactory.getLogger(ModelCallRecorder.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        modelCallLogger.addAppender(appender);
        MDC.put(TraceIdFilter.MDC_REQUEST_ID, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        modelCallLogger.detachAppender(appender);
        MDC.remove(TraceIdFilter.MDC_REQUEST_ID);
    }

    @Test
    void syncSuccessShouldLogTraceLatencyAndProviderUsageWithoutPromptOrAnswer() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "full model answer must not enter logs", "resp-sync-1",
                new DefaultUsage(12, 5, 17)));
        DeepSeekAiChatService service = service(chatModel);

        AiChatResult result = service.chat(
                new ChatInvocation(null, "full prompt must not enter logs", List.of()),
                ThinkingSettings.legacyDefault());

        assertThat(result.content()).isEqualTo("full model answer must not enter logs");
        JsonNode event = singleModelCallEvent();
        assertThat(event.path("traceId").asText()).isEqualTo(TRACE_ID);
        assertThat(event.path("provider").asText()).isEqualTo("deepseek");
        assertThat(event.path("callMode").asText()).isEqualTo("SYNC");
        assertThat(event.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(event.path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(event.path("promptTokens").asInt()).isEqualTo(12);
        assertThat(event.path("completionTokens").asInt()).isEqualTo(5);
        assertThat(event.path("totalTokens").asInt()).isEqualTo(17);
        assertThat(event.path("usageStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(event.path("providerResponseId").asText()).isEqualTo("resp-sync-1");

        String allLogs = rawLogs();
        assertThat(allLogs).doesNotContain("full prompt must not enter logs")
                .doesNotContain("full model answer must not enter logs")
                .doesNotContain("non-placeholder-observability-test-key");
    }

    @Test
    void syncErrorShouldLogOneSafeTerminalRecordAndOmitMissingUsageValues() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Authorization Bearer must-not-log"));
        DeepSeekAiChatService service = service(chatModel);

        assertThatThrownBy(() -> service.chat(
                new ChatInvocation(null, "sensitive prompt", List.of()),
                ThinkingSettings.legacyDefault()))
                .isInstanceOf(RuntimeException.class);

        JsonNode event = singleModelCallEvent();
        assertThat(event.path("status").asText()).isEqualTo("ERROR");
        assertThat(event.path("errorType").asText()).isEqualTo("RuntimeException");
        assertThat(event.path("usageStatus").asText()).isEqualTo("NOT_RETURNED");
        assertThat(event.has("promptTokens")).isFalse();
        assertThat(event.has("completionTokens")).isFalse();
        assertThat(event.has("totalTokens")).isFalse();
        assertThat(rawLogs()).doesNotContain("must-not-log").doesNotContain("sensitive prompt");
    }

    @Test
    void streamUsageShouldUseLatestSnapshotWithoutAccumulatingDuplicateChunks() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse first = response("A", "resp-stream-1", new DefaultUsage(10, 5, 15));
        ChatResponse second = response("B", "resp-stream-2", new DefaultUsage(10, 5, 15));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(first, second));
        DeepSeekAiChatService service = service(chatModel);

        List<StreamChunk> chunks = service.streamChat(
                        new ChatInvocation(null, "stream prompt", List.of()),
                        ThinkingSettings.legacyDefault())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).hasSize(2);
        JsonNode event = singleModelCallEvent();
        assertThat(event.path("callMode").asText()).isEqualTo("STREAM");
        assertThat(event.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(event.path("firstChunkLatencyMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(event.path("promptTokens").asInt()).isEqualTo(10);
        assertThat(event.path("completionTokens").asInt()).isEqualTo(5);
        assertThat(event.path("totalTokens").asInt()).isEqualTo(15);
        assertThat(event.path("providerResponseId").asText()).isEqualTo("resp-stream-2");
    }

    @Test
    void streamErrorShouldLogOnlyErrorTypeAndNoSensitiveMessageOrContent() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(response("partial answer must-not-log", null, null)),
                Flux.error(new RuntimeException("Bearer secret exception"))));
        DeepSeekAiChatService service = service(chatModel);

        assertThatThrownBy(() -> service.streamChat(
                        new ChatInvocation(null, "sensitive stream prompt", List.of()),
                        ThinkingSettings.legacyDefault())
                .collectList()
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);

        JsonNode event = singleModelCallEvent();
        assertThat(event.path("status").asText()).isEqualTo("ERROR");
        assertThat(event.path("errorType").asText()).isEqualTo("RuntimeException");
        assertThat(rawLogs()).doesNotContain("partial answer must-not-log")
                .doesNotContain("Bearer secret exception")
                .doesNotContain("sensitive stream prompt");
    }

    @Test
    void streamCancellationShouldLogOneCancelledTerminalRecord() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.concat(Flux.just(response("first", null, null)), Flux.never()));
        DeepSeekAiChatService service = service(chatModel);

        StreamChunk first = service.streamChat(
                        new ChatInvocation(null, "cancel prompt", List.of()),
                        ThinkingSettings.legacyDefault())
                .take(1)
                .blockLast(Duration.ofSeconds(5));

        assertThat(first).isInstanceOf(StreamChunk.ContentChunk.class);
        JsonNode event = singleModelCallEvent();
        assertThat(event.path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void mockUsageShouldBeExplicitlyNotSupportedWithoutTokenValues() throws Exception {
        MockAiChatService service = new MockAiChatService();

        service.streamChat("mock prompt", ThinkingSettings.legacyDefault())
                .collectList()
                .block(Duration.ofSeconds(5));

        JsonNode event = singleModelCallEvent();
        assertThat(event.path("provider").asText()).isEqualTo("offline-mock");
        assertThat(event.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(event.path("usageStatus").asText()).isEqualTo("NOT_SUPPORTED");
        assertThat(event.path("usageSource").asText()).isEqualTo("NONE");
        assertThat(event.has("promptTokens")).isFalse();
        assertThat(event.has("totalTokens")).isFalse();
    }

    private DeepSeekAiChatService service(ChatModel chatModel) {
        AiProperties properties = new AiProperties();
        properties.setMode("deepseek");
        properties.setApiKey("invalid-test-observability-value");
        properties.setModel("deepseek-flash");
        properties.getThinking().setReasonerModel("deepseek-reasoner");
        return new DeepSeekAiChatService(chatModel, properties);
    }

    private ChatResponse response(String text, String id, Usage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().id(id);
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                metadata.build());
    }

    private JsonNode singleModelCallEvent() throws Exception {
        List<JsonNode> events = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(this::parseQuietly)
                .filter(node -> node != null && "MODEL_CALL".equals(node.path("event").asText()))
                .toList();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private String rawLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);
    }

    private JsonNode parseQuietly(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }
}
