package com.zl.d38.hybrideval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 策略后端（改造自 D37 PgvectorBackend，超时/Result 口径不变）：
 * 按 strategy 选择端点——PURE_VECTOR 调既有 /api/v1/knowledge/**、/api/v1/rag/answer；
 * HYBRID_RRF 调 /api/v1/hybrid/**。删除 IN_MEMORY 后端（对照组必须与基线同链路，P4.1）。
 *
 * <p>HTTP 恒 200、以 Result.code 判成败；单请求超时：检索 30s、answer 60s，
 * TIMEOUT 计 httpOk=false 不抛断（P4.5）。混合响应额外解析 fusionTrace 摘要与
 * keyword/fusion 分段计时（P4.2）。</p>
 */
public final class StrategyBackend {

    public static final String PURE_VECTOR = "PURE_VECTOR";
    public static final String HYBRID_RRF = "HYBRID_RRF";

    public record RetrievalResult(List<Hit> hits,
                                  long retrievalElapsedMillis,
                                  long queryEmbeddingMs,
                                  long keywordSearchMs,
                                  long fusionMs,
                                  int effectiveTopK,
                                  List<String> fusionTraceDigest,
                                  boolean httpOk,
                                  boolean timedOut) {
    }

    public record RagResult(String status,
                            String decision,
                            String reasonCode,
                            int retrievalHitCount,
                            boolean mocked,
                            boolean httpOk,
                            boolean timedOut) {
    }

    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Map<Integer, String> pageToLogicalChunkId;

    public StrategyBackend(String baseUrl, ObjectMapper objectMapper,
                           Map<Integer, String> pageToLogicalChunkId) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.pageToLogicalChunkId = pageToLogicalChunkId;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public RetrievalResult search(String query, int topK, String strategy) {
        String path = HYBRID_RRF.equals(strategy)
                ? "/api/v1/hybrid/retrieval/search" : "/api/v1/knowledge/retrieval/search";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("topK", topK);
        long start = System.nanoTime();
        try {
            PostResult post = post(path, body, 30);
            if (!post.ok()) {
                return new RetrievalResult(List.of(), elapsed(start), 0L, 0L, 0L, topK,
                        List.of(), false, post.timedOut());
            }
            JsonNode root = post.body();
            if (root.path("code").asInt(-1) != 200) {
                return new RetrievalResult(List.of(), elapsed(start), 0L, 0L, 0L, topK,
                        List.of(), false, false);
            }
            JsonNode data = root.path("data");
            long retrievalElapsed = data.path("elapsedMillis")
                    .asLong(data.path("vectorElapsedMillis").asLong(0L));
            long queryEmbeddingMs = data.path("queryEmbedding").path("elapsedMillis").asLong(0L);
            long keywordMs = data.path("keywordElapsedMillis").asLong(0L);
            long fusionMs = data.path("fusionElapsedMillis").asLong(0L);
            int effectiveTopK = data.path("effectiveTopK").asInt(topK);
            List<Hit> hits = new ArrayList<>();
            List<String> digest = new ArrayList<>();
            int rank = 1;
            for (JsonNode hitNode : data.path("hits")) {
                hits.add(toHit(hitNode, rank++));
            }
            for (JsonNode traceNode : data.path("fusionTrace")) {
                digest.add(traceNode.path("chunkId").asText() + "|"
                        + traceNode.path("origin").asText() + "|"
                        + traceNode.path("vectorRank").asText("-") + "|"
                        + traceNode.path("keywordRank").asText("-"));
            }
            return new RetrievalResult(List.copyOf(hits), retrievalElapsed, queryEmbeddingMs,
                    keywordMs, fusionMs, effectiveTopK, List.copyOf(digest), true, false);
        } catch (Exception ex) {
            return new RetrievalResult(List.of(), elapsed(start), 0L, 0L, 0L, topK,
                    List.of(), false, false);
        }
    }

    public RagResult answer(String query, int topK, String strategy) {
        String path = HYBRID_RRF.equals(strategy)
                ? "/api/v1/hybrid/rag/answer" : "/api/v1/rag/answer";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("topK", topK);
        try {
            PostResult post = post(path, body, 60);
            if (!post.ok() || post.body().path("code").asInt(-1) != 200) {
                return new RagResult(null, null, null, 0, false, false, post.timedOut());
            }
            JsonNode data = post.body().path("data");
            return new RagResult(
                    textOrNull(data, "status"),
                    textOrNull(data, "decision"),
                    textOrNull(data, "reasonCode"),
                    data.path("retrievalHitCount").asInt(0),
                    data.path("mocked").asBoolean(false),
                    true, false);
        } catch (Exception ex) {
            return new RagResult(null, null, null, 0, false, false, false);
        }
    }

    /** 探测 hybrid 端点可用性（C 阶段前置门禁：404/异常 → 实验不可跑，禁止静默降级）。 */
    public boolean hybridAvailable() {
        ObjectNode probeBody = objectMapper.createObjectNode();
        probeBody.put("query", "可用性探测");
        probeBody.put("topK", 1);
        PostResult post = post("/api/v1/hybrid/retrieval/search", probeBody, 10);
        return post.ok() && post.body() != null && post.body().path("code").asInt(-1) == 200;
    }

    private record PostResult(JsonNode body, boolean timedOut) {
        boolean ok() {
            return body != null && !timedOut;
        }
    }

    private PostResult post(String path, ObjectNode body, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new PostResult(null, false);
            }
            return new PostResult(objectMapper.readTree(response.body()), false);
        } catch (java.net.http.HttpTimeoutException ex) {
            return new PostResult(null, true);
        } catch (Exception ex) {
            return new PostResult(null, false);
        }
    }

    private Hit toHit(JsonNode node, int rank) {
        List<Integer> pageNumbers = new ArrayList<>();
        node.path("pageNumbers").forEach(p -> pageNumbers.add(p.asInt()));
        List<String> logicalIds = new ArrayList<>();
        for (Integer page : pageNumbers) {
            String logical = pageToLogicalChunkId.get(page);
            if (logical != null && !logicalIds.contains(logical)) {
                logicalIds.add(logical);
            }
        }
        Double score = node.hasNonNull("score") ? node.get("score").asDouble() : null;
        Double distance = node.hasNonNull("distance") ? node.get("distance").asDouble() : null;
        return new Hit(rank,
                textOrNull(node, "chunkId"),
                textOrNull(node, "documentId"),
                textOrNull(node, "version"),
                node.hasNonNull("chunkIndex") ? node.get("chunkIndex").asInt() : null,
                textOrNull(node, "title"),
                List.copyOf(pageNumbers),
                textOrNull(node, "content"),
                score, distance, List.copyOf(logicalIds));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
