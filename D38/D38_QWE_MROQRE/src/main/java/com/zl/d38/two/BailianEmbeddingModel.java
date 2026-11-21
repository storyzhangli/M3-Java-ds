package com.zl.d38.two;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d38.common.ResultCode;
import com.zl.d38.knowledge.embedding.EmbeddingException;
import com.zl.d38.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d38.knowledge.embedding.EmbeddingVectorValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阿里百炼（BAILIAN）远程 Embedding 适配器：OpenAI compatible-mode /embeddings 协议。
 *
 * <p>D38 two_ 隔离链路专用，<b>不注册为 Spring Bean</b>：由
 * {@link TwoPgVectorConfiguration} 手动构造并注入 two_PgVectorStore，
 * 避免与本地 LOCAL 链路的唯一 EmbeddingModel Bean 产生按类型注入歧义。</p>
 *
 * <p>工程约束（与任务书 C06/C08/C09/C10 对应）：</p>
 * <ul>
 *   <li>批量上限：单次 HTTP 请求 input 数组不超过 apiBatchSize（1..10，探针实测冻结值 10）；
 *       超出时在 call() 内切子批并按 data[].index 对齐合并，全局 index 连续；</li>
 *   <li>重试：仅 429/5xx/IO 超时重试 1 次（共 2 次尝试），指数退避 + 抖动；
 *       401/403/400/404 等客户端错误立即失败，不重试；</li>
 *   <li>熔断：同一模型连续 2 次调用失败后打开熔断，后续调用直接抛
 *       {@link ResultCode#AI_UNAVAILABLE}，不再发起真实 HTTP，等待人工确认（重启复位）；</li>
 *   <li>响应校验：维度必须等于描述符冻结维度（1024）、数值有限、非零向量；
 *       探针实测服务端已 L2 归一化（norm=1），若偏离则客户端归一化并告警（不静默）；</li>
 *   <li>密钥卫生：apiKey 仅存在于请求头，不进入日志、异常消息、ledger 与 toString；
 *       服务端错误正文先剥离 Bearer 片段再截断；</li>
 *   <li>调用台账：每次真实 HTTP 尝试追加一行 JSONL 到 ledgerPath（seq/purpose/timestamp/
 *       httpStatus/tokens/elapsedMs/batchSize/attempt），供预算审计；ledgerPath 为空则只记日志。</li>
 * </ul>
 *
 * <p>baseUrl 约定：descriptor.apiUrl() 已含 /compatible-mode/v1（环境变量
 * BAILIAN_EMBEDDING_BASE_URL 注入），本类只追加 /embeddings，禁止双重 /v1。</p>
 */
@Slf4j
public class BailianEmbeddingModel implements EmbeddingModel {

    /** 同根因连续失败达到该次数即打开熔断（任务书 C09）。 */
    static final int CIRCUIT_FAILURE_THRESHOLD = 2;

    /** 单次 HTTP 尝试上限（首次 + 1 次重试）。 */
    private static final int MAX_HTTP_ATTEMPTS = 2;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ERROR_BODY = 300;

    private final EmbeddingModelDescriptor descriptor;
    private final String apiKey;
    private final int apiBatchSize;
    private final String ledgerPath;
    private final long initialBackoffMs;
    private final String embeddingsUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmbeddingVectorValidator vectorValidator = new EmbeddingVectorValidator();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Object ledgerLock = new Object();
    private volatile boolean warnedNonUnitNorm;

    public BailianEmbeddingModel(EmbeddingModelDescriptor descriptor,
                                 String apiKey,
                                 int apiBatchSize,
                                 String ledgerPath) {
        this(descriptor, apiKey, apiBatchSize, ledgerPath, 1_000L);
    }

    /** 测试可注入更短退避；生产路径使用默认 1000ms。 */
    BailianEmbeddingModel(EmbeddingModelDescriptor descriptor,
                          String apiKey,
                          int apiBatchSize,
                          String ledgerPath,
                          long initialBackoffMs) {
        if (descriptor == null) {
            throw new IllegalArgumentException("百炼 Embedding 描述符不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingException(ResultCode.AI_CONFIG_ERROR,
                    "百炼 API Key 为空：必须通过环境变量 DASHSCOPE_API_KEY 注入，禁止硬编码");
        }
        if (apiBatchSize < 1 || apiBatchSize > 10) {
            throw new IllegalArgumentException(
                    "百炼 api-batch-size 必须在 1..10（探针实测冻结上限 10），实际 " + apiBatchSize);
        }
        this.descriptor = descriptor;
        this.apiKey = apiKey.strip();
        this.apiBatchSize = apiBatchSize;
        this.ledgerPath = ledgerPath == null ? "" : ledgerPath.strip();
        this.initialBackoffMs = Math.max(1L, initialBackoffMs);
        this.embeddingsUrl = resolveEmbeddingsUrl(descriptor.apiUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request == null ? null : request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "百炼 Embedding 输入不能为空");
        }
        if (consecutiveFailures.get() >= CIRCUIT_FAILURE_THRESHOLD) {
            throw new EmbeddingException(ResultCode.AI_UNAVAILABLE,
                    "百炼 Embedding 熔断已打开（连续 " + consecutiveFailures.get()
                            + " 次调用失败），已停止真实调用，请人工确认服务状态后重启应用复位");
        }
        try {
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int from = 0; from < instructions.size(); from += apiBatchSize) {
                int to = Math.min(from + apiBatchSize, instructions.size());
                List<String> batch = instructions.subList(from, to);
                float[][] vectors = postWithRetry(batch);
                for (int i = 0; i < vectors.length; i++) {
                    embeddings.add(new Embedding(vectors[i], from + i));
                }
            }
            consecutiveFailures.set(0);
            EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
            metadata.setModel(descriptor.modelName());
            return new EmbeddingResponse(embeddings, metadata);
        } catch (EmbeddingException ex) {
            consecutiveFailures.incrementAndGet();
            throw ex;
        } catch (RuntimeException ex) {
            consecutiveFailures.incrementAndGet();
            throw new EmbeddingException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "百炼 Embedding 调用失败: " + safeMessage(ex.getMessage()), ex);
        }
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return descriptor.dimensions();
    }

    /** 当前向量空间描述符（two_ 链路装配与证据使用）。 */
    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }

    // ------------------------------------------------------------------
    // HTTP：单批请求 + 受限重试
    // ------------------------------------------------------------------

    private float[][] postWithRetry(List<String> batch) {
        String body = buildRequestBody(batch);
        EmbeddingException last = null;
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            int httpStatus = 0;
            int promptTokens = 0;
            int totalTokens = 0;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(embeddingsUrl))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                httpStatus = response.statusCode();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                if (httpStatus == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
                    totalTokens = root.path("usage").path("total_tokens").asInt(0);
                    appendLedger(httpStatus, promptTokens, totalTokens, elapsedMs,
                            batch.size(), attempt);
                    return parseVectors(root, batch.size());
                }
                appendLedger(httpStatus, 0, 0, elapsedMs, batch.size(), attempt);
                String detail = sanitizeErrorBody(response.body());
                if (httpStatus == 429 || httpStatus >= 500) {
                    last = new EmbeddingException(ResultCode.AI_UNAVAILABLE,
                            "百炼 Embedding 服务端限流/不可用 HTTP " + httpStatus + ": " + detail);
                } else {
                    // 401/403 鉴权、400 参数、404 模型不存在：客户端错误，立即失败不重试
                    throw new EmbeddingException(ResultCode.AI_CONFIG_ERROR,
                            "百炼 Embedding 请求被拒绝 HTTP " + httpStatus
                                    + "（鉴权/参数/模型名问题，不重试）: " + detail);
                }
            } catch (IOException ex) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                appendLedger(0, 0, 0, elapsedMs, batch.size(), attempt);
                last = new EmbeddingException(ResultCode.AI_UNAVAILABLE,
                        "百炼 Embedding 网络/超时失败: " + safeMessage(ex.getMessage()), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException(ResultCode.AI_UNAVAILABLE,
                        "百炼 Embedding 调用被中断", ex);
            }
            if (attempt < MAX_HTTP_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw last == null
                ? new EmbeddingException(ResultCode.AI_UNAVAILABLE, "百炼 Embedding 调用失败")
                : last;
    }

    private String buildRequestBody(List<String> batch) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", descriptor.modelName());
        ArrayNode input = root.putArray("input");
        for (String text : batch) {
            if (text == null || text.isBlank()) {
                throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                        "百炼 Embedding 输入存在空白文本");
            }
            input.add(text);
        }
        root.put("encoding_format", "float");
        return root.toString();
    }

    /**
     * 解析 data[]：按 index 对齐（服务端可能乱序），校验条数、维度、有限值与范数。
     */
    private float[][] parseVectors(JsonNode root, int expectedCount) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expectedCount) {
            throw new EmbeddingException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "百炼 Embedding 响应条数不一致，期望 " + expectedCount
                            + "，实际 " + (data.isArray() ? data.size() : "非数组"));
        }
        float[][] vectors = new float[expectedCount][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= expectedCount || vectors[index] != null) {
                throw new EmbeddingException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                        "百炼 Embedding 响应 index 非法或重复: " + index);
            }
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray()) {
                throw new EmbeddingException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                        "百炼 Embedding 响应缺少 embedding 数组, index=" + index);
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectorValidator.validate(vector, descriptor.dimensions());
            normalizeIfDeviated(vector);
            vectors[index] = vector;
        }
        return vectors;
    }

    /**
     * 探针实测服务端已 L2 归一化（norm=1）。若偏离超过 1e-3，客户端归一化并告警一次，
     * 保证 COSINE 检索语义稳定；该决策已登记于 D38 证据与文档，不静默处理。
     */
    private void normalizeIfDeviated(float[] vector) {
        double norm = vectorValidator.l2Norm(vector);
        if (Math.abs(norm - 1.0) <= 1.0e-3) {
            return;
        }
        if (!warnedNonUnitNorm) {
            warnedNonUnitNorm = true;
            log.warn("百炼 Embedding 向量范数偏离 1（norm={}），客户端执行 L2 归一化；"
                    + "与探针冻结值不一致，请核对服务端模型版本", norm);
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    private void sleepBackoff(int attempt) {
        long base = initialBackoffMs * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 5));
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(ResultCode.AI_UNAVAILABLE,
                    "百炼 Embedding 重试等待被中断", ex);
        }
    }

    // ------------------------------------------------------------------
    // 调用台账（JSONL 追加，禁止密钥）
    // ------------------------------------------------------------------

    private void appendLedger(int httpStatus, int promptTokens, int totalTokens,
                              long elapsedMs, int batchSize, int attempt) {
        log.info("BAILIAN_CALL status={} promptTokens={} totalTokens={} elapsedMs={} batchSize={} attempt={}",
                httpStatus, promptTokens, totalTokens, elapsedMs, batchSize, attempt);
        if (ledgerPath.isEmpty()) {
            return;
        }
        synchronized (ledgerLock) {
            try {
                Path path = Path.of(ledgerPath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                long seq = Files.exists(path) ? Files.readAllLines(path).size() + 1 : 1;
                String line = ("{\"seq\":%d,\"purpose\":\"app-embed\",\"timestamp\":\"%s\","
                        + "\"httpStatus\":%d,\"promptTokens\":%d,\"totalTokens\":%d,"
                        + "\"elapsedMs\":%d,\"batchSize\":%d,\"attempt\":%d}%n")
                        .formatted(seq,
                                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                httpStatus, promptTokens, totalTokens, elapsedMs, batchSize, attempt);
                Files.writeString(path, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ex) {
                // 台账失败不阻断业务调用，但必须可见
                log.warn("百炼调用台账写入失败 path={} reason={}", ledgerPath, safeMessage(ex.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * baseUrl 已含 /compatible-mode/v1，只追加 /embeddings；若调用方已给全路径则不重复追加。
     */
    static String resolveEmbeddingsUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new EmbeddingException(ResultCode.AI_CONFIG_ERROR,
                    "百炼 baseUrl 为空：必须通过环境变量 BAILIAN_EMBEDDING_BASE_URL 注入");
        }
        String url = apiUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new EmbeddingException(ResultCode.AI_CONFIG_ERROR,
                    "百炼 baseUrl 协议非法（仅允许 http/https）");
        }
        return url.endsWith("/embeddings") ? url : url + "/embeddings";
    }

    /** 剥离可能的 Bearer 凭据片段并截断，禁止密钥进入异常与日志。 */
    static String sanitizeErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String cleaned = body.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***");
        cleaned = cleaned.replaceAll("sk-[A-Za-z0-9._\\-]+", "sk-***");
        return cleaned.length() > MAX_ERROR_BODY
                ? cleaned.substring(0, MAX_ERROR_BODY) + "...(truncated)"
                : cleaned;
    }

    private static String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = sanitizeErrorBody(message);
        return cleaned;
    }
}