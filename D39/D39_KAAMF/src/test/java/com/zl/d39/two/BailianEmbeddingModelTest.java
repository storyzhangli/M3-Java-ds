package com.zl.d39.two;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zl.d39.common.ResultCode;
import com.zl.d39.knowledge.embedding.*;
import com.zl.d39.knowledge.embedding.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BailianEmbeddingModel 零计费桩测试（任务书 C08：surefire 不产生任何真实 API 调用）。
 *
 * <p>使用 JDK 内置 HttpServer 在 127.0.0.1 随机端口模拟百炼 compatible-mode /embeddings：
 * 覆盖成功解析、乱序 index 对齐、401 不重试、429 重试、维度错误拒绝、批量切分、
 * 熔断、URL 拼接、密钥卫生与向量空间隔离。测试描述符维度为 4（桩夹具），
 * 与生产冻结维度 1024 无关——维度校验逻辑本身是被测对象。</p>
 */
class BailianEmbeddingModelTest {

    private static final String TEST_KEY = "sk-unit-test-secret";
    private static final int DIMS = 4;

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> requestBodies = new ArrayList<>();
    private volatile Function<String, StubReply> handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private record StubReply(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestCount.incrementAndGet();
            synchronized (requestBodies) {
                requestBodies.add(body);
            }
            StubReply reply = handler.apply(body);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BailianEmbeddingModel newModel(String ledgerPath) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1";
        EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor(
                EmbeddingProvider.BAILIAN, "qwen3.7-text-embedding", "current", DIMS,
                8192, EmbeddingLengthUnit.TOKENS,
                VectorSpaceIdentity.create(EmbeddingProvider.BAILIAN,
                        "qwen3.7-text-embedding", "current", DIMS),
                "two-bailian-test-v1", DistanceMetric.COSINE, null, true, baseUrl);
        return new BailianEmbeddingModel(descriptor, TEST_KEY, 10, ledgerPath, 10L);
    }

    private EmbeddingRequest request(int count) {
        List<String> texts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            texts.add("探针文本-" + i);
        }
        return new EmbeddingRequest(texts, EmbeddingOptions.builder().build());
    }

    /** 生成 count 个单位向量（第 i%4 维为 1），按 reverseOrder 决定是否乱序返回。 */
    private String successBody(int count, boolean reverseOrder) {
        StringBuilder data = new StringBuilder();
        for (int k = 0; k < count; k++) {
            int index = reverseOrder ? count - 1 - k : k;
            float[] vector = new float[DIMS];
            vector[index % DIMS] = 1.0f;
            if (k > 0) {
                data.append(',');
            }
            data.append("{\"embedding\":[").append(vector[0]).append(',').append(vector[1])
                    .append(',').append(vector[2]).append(',').append(vector[3])
                    .append("],\"index\":").append(index).append('}');
        }
        return "{\"data\":[" + data + "],\"model\":\"qwen3.7-text-embedding\","
                + "\"usage\":{\"prompt_tokens\":" + count + ",\"total_tokens\":" + count + "}}";
    }

    @Test
    @DisplayName("成功解析：乱序 index 按序对齐，维度与范数正确")
    void successWithShuffledIndex() {
        handler = body -> new StubReply(200, successBody(2, true));
        EmbeddingResponse response = newModel("").call(request(2));
        assertEquals(2, response.getResults().size());
        assertEquals(0, response.getResults().get(0).getIndex());
        assertEquals(1, response.getResults().get(1).getIndex());
        // index 0 的向量应为 [1,0,0,0]，index 1 为 [0,1,0,0]（服务端乱序返回仍对齐）
        assertEquals(1.0f, response.getResults().get(0).getOutput()[0], 1e-6);
        assertEquals(1.0f, response.getResults().get(1).getOutput()[1], 1e-6);
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("401 鉴权失败：立即失败不重试，异常消息不含密钥")
    void authFailureNoRetry() {
        handler = body -> new StubReply(401, "{\"error\":{\"message\":\"Invalid API-key\"}}");
        BailianEmbeddingModel model = newModel("");
        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        assertEquals(ResultCode.AI_CONFIG_ERROR, ex.getResultCode());
        assertEquals(1, requestCount.get());
        assertFalse(ex.getMessage().contains(TEST_KEY));
    }

    @Test
    @DisplayName("429 限流：重试 1 次后成功，共 2 次 HTTP")
    void rateLimitThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        handler = body -> calls.incrementAndGet() == 1
                ? new StubReply(429, "{\"error\":{\"message\":\"Throttling\"}}")
                : new StubReply(200, successBody(1, false));
        EmbeddingResponse response = newModel("").call(request(1));
        assertEquals(1, response.getResults().size());
        assertEquals(2, requestCount.get());
    }

    @Test
    @DisplayName("维度错误：响应维度与冻结维度不一致即拒绝")
    void dimensionMismatchRejected() {
        handler = body -> new StubReply(200,
                "{\"data\":[{\"embedding\":[1.0,0.0,0.0],\"index\":0}]}");
        BailianEmbeddingModel model = newModel("");
        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        assertEquals(ResultCode.EMBEDDING_RESPONSE_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("维度"));
    }

    @Test
    @DisplayName("批量切分：12 条输入按上限 10 切为 10+2 两批，全局 index 连续")
    void batchSplitting() {
        handler = body -> {
            try {
                JsonNode root = objectMapper.readTree(body);
                int size = root.path("input").size();
                return new StubReply(200, successBody(size, false));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        };
        EmbeddingResponse response = newModel("").call(request(12));
        assertEquals(12, response.getResults().size());
        assertEquals(2, requestCount.get());
        for (int i = 0; i < 12; i++) {
            assertEquals(i, response.getResults().get(i).getIndex());
        }
        synchronized (requestBodies) {
            try {
                assertEquals(10, objectMapper.readTree(requestBodies.get(0)).path("input").size());
                assertEquals(2, objectMapper.readTree(requestBodies.get(1)).path("input").size());
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Test
    @DisplayName("熔断：连续 2 次调用失败后第 3 次直接拒绝，不再发起 HTTP")
    void circuitBreakerOpens() {
        handler = body -> new StubReply(500, "{\"error\":{\"message\":\"InternalError\"}}");
        BailianEmbeddingModel model = newModel("");
        assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        int before = requestCount.get();
        assertEquals(4, before); // 每次调用 2 次 HTTP 尝试（首次+重试）
        EmbeddingException ex = assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        assertEquals(ResultCode.AI_UNAVAILABLE, ex.getResultCode());
        assertTrue(ex.getMessage().contains("熔断"));
        assertEquals(before, requestCount.get());
    }

    @Test
    @DisplayName("台账：JSONL 追加且不含密钥；401 也落账")
    void ledgerAppendedWithoutSecret(@TempDir Path tempDir) throws IOException {
        Path ledger = tempDir.resolve("call-ledger.jsonl");
        handler = body -> new StubReply(200, successBody(1, false));
        BailianEmbeddingModel model = newModel(ledger.toString());
        model.call(request(1));
        handler = body -> new StubReply(401, "{\"error\":{\"message\":\"Invalid API-key\"}}");
        assertThrows(EmbeddingException.class, () -> model.call(request(1)));
        List<String> lines = Files.readAllLines(ledger);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"httpStatus\":200"));
        assertTrue(lines.get(0).contains("\"seq\":1"));
        assertTrue(lines.get(1).contains("\"httpStatus\":401"));
        assertTrue(lines.get(1).contains("\"seq\":2"));
        String content = String.join("\n", lines);
        assertFalse(content.contains(TEST_KEY));
        assertFalse(content.contains("Bearer"));
    }

    @Test
    @DisplayName("URL 拼接：base 已含 /compatible-mode/v1 只追加 /embeddings，禁止双重 /v1")
    void urlResolution() {
        assertEquals("https://host/compatible-mode/v1/embeddings",
                BailianEmbeddingModel.resolveEmbeddingsUrl("https://host/compatible-mode/v1"));
        assertEquals("https://host/compatible-mode/v1/embeddings",
                BailianEmbeddingModel.resolveEmbeddingsUrl("https://host/compatible-mode/v1/"));
        assertEquals("https://host/compatible-mode/v1/embeddings",
                BailianEmbeddingModel.resolveEmbeddingsUrl("https://host/compatible-mode/v1/embeddings"));
        assertThrows(EmbeddingException.class,
                () -> BailianEmbeddingModel.resolveEmbeddingsUrl("ftp://host/v1"));
        assertThrows(EmbeddingException.class,
                () -> BailianEmbeddingModel.resolveEmbeddingsUrl(""));
    }

    @Test
    @DisplayName("密钥卫生：错误正文剥离 sk- 与 Bearer 片段")
    void errorBodySanitized() {
        String sanitized = BailianEmbeddingModel.sanitizeErrorBody(
                "key sk-abc123XYZ leaked; Authorization: Bearer sk-abc123XYZ");
        assertFalse(sanitized.contains("sk-abc123XYZ"));
        assertTrue(sanitized.contains("***"));
    }

    @Test
    @DisplayName("空间隔离：BAILIAN 与 LOCAL 描述符 vectorSpaceId 不同")
    void vectorSpaceIsolation() {
        String bailian = VectorSpaceIdentity.create(EmbeddingProvider.BAILIAN,
                "qwen3.7-text-embedding", "current", 1024);
        String local = VectorSpaceIdentity.create(EmbeddingProvider.LOCAL,
                "local-hash-ngram-v1", "2026.11", 384);
        assertNotEquals(bailian, local);
        assertTrue(bailian.startsWith("vs_"));
    }

    @Test
    @DisplayName("空密钥构造即失败（fail-fast，禁止空密钥装配）")
    void blankKeyRejectedAtConstruction() {
        EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor(
                EmbeddingProvider.BAILIAN, "qwen3.7-text-embedding", "current", DIMS,
                8192, EmbeddingLengthUnit.TOKENS, "vs_test", "two-bailian-test-v1",
                DistanceMetric.COSINE, null, true, "http://127.0.0.1:1/v1");
        EmbeddingException ex = assertThrows(EmbeddingException.class,
                () -> new BailianEmbeddingModel(descriptor, " ", 10, ""));
        assertEquals(ResultCode.AI_CONFIG_ERROR, ex.getResultCode());
    }
}