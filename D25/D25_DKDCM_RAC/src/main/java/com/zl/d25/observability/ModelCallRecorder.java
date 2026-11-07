package com.zl.d25.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d25.logging.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个逻辑模型调用的脱敏终态记录器。
 *
 * <p>日志字段采用白名单，不接收 Prompt、用户原文、模型正文、推理内容或异常 message。
 * 每个 Call 使用原子守卫保证 SUCCESS/ERROR/CANCELLED 只写一条终态记录。</p>
 */
public final class ModelCallRecorder {

    public static final String LOGGER_NAME = "MODEL_CALL";

    private static final Logger modelCallLogger = LoggerFactory.getLogger(LOGGER_NAME);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ModelCallRecorder() {
    }

    public enum CallMode {
        SYNC,
        STREAM
    }

    public enum Status {
        SUCCESS,
        ERROR,
        CANCELLED
    }

    /**
     * 在模型调用即将开始的位置创建。TraceId 来自当前请求线程，并在异步流生命周期内保持不变。
     */
    public static Call start(String provider, String model, CallMode mode) {
        return start(TraceIdFilter.currentTraceId(), provider, model, mode);
    }

    /** 使用在请求线程预先捕获的 TraceId 创建，避免在 Reactor 回调线程读取 ThreadLocal。 */
    public static Call start(String traceId, String provider, String model, CallMode mode) {
        return new Call(traceId, provider, model, mode);
    }

    public static final class Call {

        private final String traceId;
        private final String callId = UUID.randomUUID().toString().replace("-", "");
        private final String provider;
        private final String model;
        private final CallMode callMode;
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<ModelTokenUsage> usage =
                new AtomicReference<>(ModelTokenUsage.notReturned());
        private final AtomicReference<String> providerResponseId = new AtomicReference<>();
        private final AtomicLong firstChunkLatencyMs = new AtomicLong(-1);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Call(String traceId, String provider, String model, CallMode callMode) {
            this.traceId = traceId;
            this.provider = provider;
            this.model = model;
            this.callMode = callMode;
        }

        /** 观察 Provider 返回的 metadata；流式多次调用时保留最新非空 usage 快照。 */
        public void observe(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return;
            }
            String responseId = sanitizeProviderResponseId(response.getMetadata().getId());
            if (responseId != null) {
                providerResponseId.set(responseId);
            }
            ModelTokenUsage observed = TokenUsageExtractor.from(response);
            if (observed.status() != ModelTokenUsage.UsageStatus.NOT_RETURNED) {
                usage.set(observed);
            }
        }

        /** Provider 明确不支持 usage 时使用；不会产生任何 token 数值。 */
        public void observeUnsupportedUsage() {
            usage.set(ModelTokenUsage.notSupported());
        }

        /** 只在收到首个真实非空输出时调用一次。 */
        public void markFirstChunk() {
            long elapsedNanos = System.nanoTime() - startedNanos;
            firstChunkLatencyMs.compareAndSet(-1, elapsedNanos / 1_000_000);
        }

        public void success() {
            finish(Status.SUCCESS, null);
        }

        public void failure(Throwable throwable) {
            finish(Status.ERROR, throwable);
        }

        public void cancelled() {
            finish(Status.CANCELLED, null);
        }

        public void finishForSignal(SignalType signal) {
            if (signal == SignalType.ON_ERROR) {
                // 错误异常由 doOnError 先传入，避免在响应式链中再次构造错误详情
                return;
            }
            if (signal == SignalType.CANCEL) {
                cancelled();
            }
        }

        private void finish(Status status, Throwable throwable) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            Instant finishedAt = Instant.now();
            long elapsedMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "MODEL_CALL");
            putIfText(event, "traceId", traceId);
            event.put("callId", callId);
            event.put("provider", provider);
            event.put("model", model);
            event.put("callMode", callMode.name());
            event.put("startedAt", startedAt.toString());
            event.put("finishedAt", finishedAt.toString());
            event.put("elapsedMs", elapsedMs);
            event.put("status", status.name());
            if (throwable != null) {
                event.put("errorType", safeErrorType(throwable));
            }
            long firstChunk = firstChunkLatencyMs.get();
            if (firstChunk >= 0) {
                event.put("firstChunkLatencyMs", firstChunk);
            }
            putIfText(event, "providerResponseId", providerResponseId.get());

            ModelTokenUsage observedUsage = usage.get();
            event.put("usageStatus", observedUsage.status().name());
            event.put("usageSource", observedUsage.source().name());
            putIfPresent(event, "promptTokens", observedUsage.promptTokens());
            putIfPresent(event, "completionTokens", observedUsage.completionTokens());
            putIfPresent(event, "totalTokens", observedUsage.totalTokens());
            if (observedUsage.totalTokensDerived()) {
                event.put("totalTokensDerived", true);
            }

            String previousTraceId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
            boolean hadPreviousTraceId = previousTraceId != null;
            try {
                if (StringUtils.hasText(traceId)) {
                    MDC.put(TraceIdFilter.MDC_REQUEST_ID, traceId);
                }
                modelCallLogger.info(OBJECT_MAPPER.writeValueAsString(event));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
                // 白名单字段均为简单标量；兜底也不输出 Prompt/异常 message
                modelCallLogger.warn("{\"event\":\"MODEL_CALL\",\"status\":\"ERROR\","
                        + "\"errorType\":\"LogSerializationException\"}");
            } finally {
                if (hadPreviousTraceId) {
                    MDC.put(TraceIdFilter.MDC_REQUEST_ID, previousTraceId);
                } else {
                    MDC.remove(TraceIdFilter.MDC_REQUEST_ID);
                }
            }
        }

        private String sanitizeProviderResponseId(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.length() > 128 || !trimmed.matches("[A-Za-z0-9._:-]+")) {
                return null;
            }
            return trimmed;
        }

        private String safeErrorType(Throwable throwable) {
            String simpleName = throwable.getClass().getSimpleName();
            return StringUtils.hasText(simpleName) ? simpleName : "UNKNOWN";
        }

        private void putIfText(Map<String, Object> event, String key, String value) {
            if (StringUtils.hasText(value)) {
                event.put(key, value);
            }
        }

        private void putIfPresent(Map<String, Object> event, String key, Integer value) {
            if (value != null) {
                event.put(key, value);
            }
        }
    }
}
