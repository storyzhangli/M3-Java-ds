package com.zl.d37.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * PGVECTOR 后端：经 HTTP 调用真实检索与 RAG 端点（黑盒，不依赖 Spring Bean）。
 *
 * <p>统一包装 {@code Result<T>} 的 code/msg/data 在客户端解析；HTTP 层恒 200，
 * 链路状态由业务码与 data.status 承载。显式超时：检索 30s、端到端 60s。</p>
 */
public final class PgvectorBackend {

    public record RetrievalResult(List<Hit> hits,
                                  long retrievalElapsedMillis,
                                  long queryEmbeddingMs,
                                  int effectiveTopK,
                                  boolean httpOk) {
    }

    public record RagResult(String status,
                            String decision,
                            String reasonCode,
                            int retrievalHitCount,
                            long retrievalElapsedMillis,
                            long totalElapsedMillis,
                            boolean mocked,
                            List<JsonNode> sources,
                            boolean httpOk) {
    }

    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Map<Integer, String> pageToLogicalChunkId;

    public PgvectorBackend(String baseUrl, ObjectMapper objectMapper,
                           Map<Integer, String> pageToLogicalChunkId) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.pageToLogicalChunkId = pageToLogicalChunkId;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public RetrievalResult search(String query, int topK) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("topK", topK);
        try {
            JsonNode root = post("/api/v1/knowledge/retrieval/search", body, 30);
            if (root == null) {
                return new RetrievalResult(List.of(), 0L, 0L, topK, false);
            }
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                return new RetrievalResult(List.of(), 0L, 0L, topK, false);
            }
            JsonNode data = root.path("data");
            long retrievalElapsed = data.path("elapsedMillis").asLong(0L);
            long queryEmbeddingMs = data.path("queryEmbedding").path("elapsedMillis").asLong(0L);
            int effectiveTopK = data.path("effectiveTopK").asInt(topK);
            List<Hit> hits = new ArrayList<>();
            int rank = 1;
            for (JsonNode hitNode : data.path("hits")) {
                hits.add(toHit(hitNode, rank++));
            }
            return new RetrievalResult(List.copyOf(hits), retrievalElapsed,
                    queryEmbeddingMs, effectiveTopK, true);
        } catch (Exception ex) {
            return new RetrievalResult(List.of(), 0L, 0L, topK, false);
        }
    }

    public RagResult answer(String query, Integer topK) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        if (topK != null) {
            body.put("topK", topK);
        }
        try {
            JsonNode root = post("/api/v1/rag/answer", body, 60);
            if (root == null) {
                return new RagResult(null, null, null, 0, 0L, 0L, false, List.of(), false);
            }
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                return new RagResult(null, null, null, 0, 0L, 0L, false, List.of(), false);
            }
            JsonNode data = root.path("data");
            List<JsonNode> sources = new ArrayList<>();
            data.path("sources").forEach(sources::add);
            return new RagResult(
                    textOrNull(data, "status"),
                    textOrNull(data, "decision"),
                    textOrNull(data, "reasonCode"),
                    data.path("retrievalHitCount").asInt(0),
                    data.path("retrievalElapsedMillis").asLong(0L),
                    data.path("elapsedMillis").asLong(0L),
                    data.path("mocked").asBoolean(false),
                    List.copyOf(sources),
                    true);
        } catch (Exception ex) {
            return new RagResult(null, null, null, 0, 0L, 0L, false, List.of(), false);
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
        Double distance = node.hasNonNull("distance") ? node.get("distance").asDouble()
                : (score == null ? null : 1.0 - score);
        return new Hit(
                rank,
                textOrNull(node, "chunkId"),
                textOrNull(node, "documentId"),
                textOrNull(node, "version"),
                node.hasNonNull("chunkIndex") ? node.get("chunkIndex").asInt() : null,
                textOrNull(node, "title"),
                List.copyOf(pageNumbers),
                textOrNull(node, "content"),
                score,
                distance,
                List.copyOf(logicalIds));
    }

    private JsonNode post(String path, ObjectNode body, int timeoutSeconds) {
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
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
