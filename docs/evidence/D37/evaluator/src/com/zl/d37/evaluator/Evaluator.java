package com.zl.d37.evaluator;

import com.zl.d37.evaluator.InMemoryBackend.SearchResult;
import com.zl.d37.evaluator.PgvectorBackend.RagResult;
import com.zl.d37.evaluator.PgvectorBackend.RetrievalResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单题评测与判题：检索 → 命中/相关判定 → 失败标签 → 四态结论。
 *
 * <p>判题基准只有两个：文档级命中与 chunk 级（逻辑片段）命中；chunk 级经页码反查
 * 逻辑片段 ID，不依赖后端 chunkId 文本。失败标签全部由原始记录可复算，不手填。</p>
 */
public final class Evaluator {

    /** 原始记录（每题每轮一条，含检索后端标记）。 */
    public record RawRecord(
            String questionId,
            String scenario,
            int round,
            String retrievalBackend,
            int hitCount,
            int returnedDocumentCount,
            int returnedChunkCount,
            int relevantDocumentCount,
            int relevantChunkCount,
            int noiseCount,
            boolean documentHit,
            boolean chunkHit,
            String expectedDecision,
            String actualDecision,
            String actualStatus,
            String reasonCode,
            List<String> labels,
            long queryEmbeddingMs,
            long retrievalElapsedMillis,
            long totalElapsedMillis,
            long inMemorySearchMs,
            List<String> hitDocumentIds,
            List<String> hitLogicalChunkIds,
            List<Double> hitScores,
            List<String> expectedDocumentIds,
            List<String> expectedChunkIds,
            String verdict) {
    }

    /** 单题聚合结果。 */
    public record QuestionOutcome(EvalQuestion question, List<RawRecord> rounds) {
    }

    private static final Set<String> CONTROLLED_DECISIONS =
            Set.of("CLARIFY", "REFUSE", "CONFLICT");

    private final String retrievalBackend;

    public Evaluator(String retrievalBackend) {
        this.retrievalBackend = retrievalBackend;
    }

    /**
     * 执行一轮检索判题（内存后端）。
     */
    public RawRecord evaluateMemory(InMemoryBackend backend, EvalQuestion question,
                                    int round, int topK, double threshold) {
        SearchResult result = backend.search(question.question(), topK, threshold);
        return buildRecord(question, round, result.hits(), -1L, -1L, -1L,
                result.inMemorySearchMs(), null, null, null, null);
    }

    /**
     * 执行一轮检索 + RAG 判题（PGVECTOR 后端）。
     */
    public RawRecord evaluatePgvector(PgvectorBackend backend, EvalQuestion question,
                                      int round, int topK) {
        long totalStart = System.nanoTime();
        RetrievalResult retrieval = backend.search(question.question(), topK);
        RagResult rag = backend.answer(question.question(), topK);
        long totalElapsed = (System.nanoTime() - totalStart) / 1_000_000L;
        return buildRecord(question, round, retrieval.hits(),
                retrieval.queryEmbeddingMs(), retrieval.retrievalElapsedMillis(),
                totalElapsed, -1L,
                rag.httpOk() ? rag.status() : null,
                rag.httpOk() ? rag.decision() : null,
                rag.httpOk() ? rag.reasonCode() : null,
                rag.httpOk() ? rag.mocked() : null);
    }

    private RawRecord buildRecord(EvalQuestion question, int round, List<Hit> hits,
                                  long queryEmbeddingMs, long retrievalElapsedMillis,
                                  long totalElapsedMillis, long inMemorySearchMs,
                                  String actualStatus, String actualDecision,
                                  String reasonCode, Boolean mocked) {
        Set<String> hitDocuments = new LinkedHashSet<>();
        Set<String> hitChunks = new LinkedHashSet<>();
        List<Double> scores = new ArrayList<>();
        for (Hit hit : hits) {
            if (hit.documentId() != null) {
                hitDocuments.add(hit.documentId());
            }
            for (String logical : hit.logicalChunkIds()) {
                hitChunks.add(logical);
            }
            if (hit.score() != null) {
                scores.add(hit.score());
            }
        }

        List<String> expectedDocs = question.expectedDocumentIds() == null
                ? List.of() : question.expectedDocumentIds();
        List<String> expectedChunks = question.expectedChunkIds() == null
                ? List.of() : question.expectedChunkIds();

        Set<String> expectedDocSet = new LinkedHashSet<>(expectedDocs);
        Set<String> expectedChunkSet = new LinkedHashSet<>(expectedChunks);

        int relevantDocuments = 0;
        for (String doc : hitDocuments) {
            if (expectedDocSet.contains(doc)) {
                relevantDocuments++;
            }
        }
        int relevantChunks = 0;
        for (String chunk : hitChunks) {
            if (expectedChunkSet.contains(chunk)) {
                relevantChunks++;
            }
        }

        boolean documentHit = expectedDocSet.isEmpty() ? false : relevantDocuments >= 1;
        boolean chunkHit = expectedChunkSet.isEmpty() ? false : relevantChunks >= 1;

        int returnedChunkCount = hitChunks.size();
        int returnedDocumentCount = hitDocuments.size();
        int noiseCount = returnedChunkCount - relevantChunks;
        if (expectedChunkSet.isEmpty()) {
            // 无 chunk 标注按文档级判噪声
            noiseCount = returnedDocumentCount - relevantDocuments;
        }

        List<String> labels = label(question, hits, expectedDocSet, expectedChunkSet,
                documentHit, chunkHit, relevantChunks, returnedChunkCount, noiseCount,
                actualDecision, actualStatus, reasonCode, mocked);

        String verdict = verdict(question, documentHit, chunkHit, actualDecision);

        return new RawRecord(
                question.questionId(),
                question.scenario(),
                round,
                retrievalBackend,
                hits.size(),
                returnedDocumentCount,
                returnedChunkCount,
                relevantDocuments,
                relevantChunks,
                noiseCount,
                documentHit,
                chunkHit,
                question.expectedDecision(),
                actualDecision,
                actualStatus,
                reasonCode,
                List.copyOf(labels),
                queryEmbeddingMs,
                retrievalElapsedMillis,
                totalElapsedMillis,
                inMemorySearchMs,
                List.copyOf(hitDocuments),
                List.copyOf(hitChunks),
                List.copyOf(scores),
                List.copyOf(expectedDocs),
                List.copyOf(expectedChunks),
                verdict);
    }

    /**
     * 失败标签（9 类一阶 + 细分原因码，由原始字段复算）。
     */
    private List<String> label(EvalQuestion question, List<Hit> hits,
                               Set<String> expectedDocs, Set<String> expectedChunks,
                               boolean documentHit, boolean chunkHit,
                               int relevantChunks, int returnedChunks, int noiseCount,
                               String actualDecision, String actualStatus,
                               String reasonCode, Boolean mocked) {
        List<String> labels = new ArrayList<>();

        // 1. 漏召回 MISS_RECALL
        boolean hasExpectation = !expectedDocs.isEmpty() || !expectedChunks.isEmpty();
        if (hasExpectation && !documentHit && !chunkHit) {
            if (hits.isEmpty()) {
                labels.add("MISS_RECALL_EMPTY");
            } else if (hits.stream().allMatch(h -> h.score() != null && h.score() < 0.15)) {
                labels.add("MISS_RECALL_LOW_SCORE");
            } else {
                labels.add("MISS_RECALL_FILTERED");
            }
        }

        // 2. 噪声过高 HIGH_NOISE
        if (returnedChunks > 0) {
            double precision = (double) relevantChunks / returnedChunks;
            if (precision < 0.5) {
                labels.add("HIGH_NOISE_PRECISION");
            }
        }
        if (!hits.isEmpty() && !expectedDocs.isEmpty()) {
            String topDocument = hits.get(0).documentId();
            if (topDocument != null && !expectedDocs.contains(topDocument)) {
                labels.add("HIGH_NOISE_TOP1");
            }
        }

        // 3. 引用伪造 FABRICATED_CITATION（离线 mock 不产生真实引用，此标签全量才可能触发）
        if (Boolean.TRUE.equals(mocked)) {
            // mock 不回填真实引用，不判伪造
        } else if (mocked != null && !mocked) {
            // 全量：reasonCode 映射
            if ("FABRICATED_URL".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_FABRICATED_URL");
            } else if ("UNSUPPORTED_CLAIM".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_UNSUPPORTED");
            } else if ("INVALID_CITATION".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_UNKNOWN_ID");
            }
        }

        // 4. 拒答错误 REFUSAL_ERROR
        if (actualDecision != null) {
            boolean expectedAnswer = "ANSWER".equals(question.expectedDecision());
            boolean actualAnswer = "ANSWER".equals(actualDecision);
            if (expectedAnswer && !actualAnswer) {
                labels.add("REFUSAL_FALSE_NEGATIVE");
            } else if (!expectedAnswer && actualAnswer) {
                labels.add("REFUSAL_FALSE_POSITIVE");
            } else if (!expectedAnswer && !actualAnswer
                    && !question.expectedDecision().equals(actualDecision)
                    && !"BOUNDARY".equals(question.scenario())) {
                labels.add("REFUSAL_WRONG_CODE");
            }
        }

        // 5. 澄清错误 WRONG_CLARIFICATION
        if (actualDecision != null) {
            boolean expectedClarify = "CLARIFY".equals(question.expectedDecision());
            boolean actualClarify = "CLARIFY".equals(actualDecision);
            if (expectedClarify && !actualClarify) {
                labels.add("CLARIFY_EXPECTED_MISSING");
            } else if (!expectedClarify && actualClarify) {
                labels.add("CLARIFY_UNEXPECTED");
            }
        }

        // 6. 元数据缺失 METADATA_INCOMPLETE
        for (Hit hit : hits) {
            if (hit.documentId() == null || hit.chunkId() == null
                    || hit.title() == null || hit.content() == null) {
                labels.add("METADATA_REQUIRED_MISSING");
                break;
            }
        }
        for (Hit hit : hits) {
            if (hit.pageNumbers() == null || hit.pageNumbers().isEmpty()) {
                labels.add("METADATA_PAGE_LOCATION_MISSING");
                break;
            }
        }

        // 8. 模型错误 MODEL_ERROR / 9. 检索异常 RETRIEVAL_ERROR
        // 仅当存在 RAG 后端交互信号时判定（内存后端四信号全 null，不判模型/检索错误）
        boolean hasRagSignal = actualDecision != null || actualStatus != null
                || reasonCode != null || mocked != null;
        if (hasRagSignal) {
            if ("GENERATION_FAILED".equals(reasonCode)) {
                labels.add("MODEL_ERROR_GENERIC");
            } else if (actualDecision == null && reasonCode == null && !hits.isEmpty()
                    && !"SUCCESS".equals(actualStatus)) {
                labels.add("MODEL_ERROR_EMPTY");
            }
            if ("RETRIEVAL_FAILED".equals(actualStatus)) {
                labels.add("RETRIEVAL_BACKEND_FAIL");
            }
        }

        return dedupe(labels);
    }

    private List<String> dedupe(List<String> labels) {
        Set<String> set = new LinkedHashSet<>(labels);
        return List.copyOf(set);
    }

    /**
     * 四态结论：PASS / FAIL / BLOCKED / NOT RUN。
     */
    private String verdict(EvalQuestion question, boolean documentHit, boolean chunkHit,
                           String actualDecision) {
        String scenario = question.scenario();
        boolean hit = documentHit || chunkHit;
        switch (scenario) {
            case "ANSWERABLE", "TERMINOLOGY" -> {
                // 期望命中且可答
                if (!hit) {
                    return "FAIL";
                }
                if (actualDecision != null && !"ANSWER".equals(actualDecision)) {
                    return "FAIL";
                }
                return "PASS";
            }
            case "UNANSWERABLE" -> {
                // 期望不命中且受控拒答
                if (hit) {
                    return "FAIL";
                }
                if (actualDecision != null && !CONTROLLED_DECISIONS.contains(actualDecision)) {
                    return "FAIL";
                }
                return "PASS";
            }
            case "BOUNDARY" -> {
                // 期望受控不答（CLARIFY/REFUSE/CONFLICT 任一）
                if (actualDecision != null && CONTROLLED_DECISIONS.contains(actualDecision)) {
                    return "PASS";
                }
                if (actualDecision == null) {
                    return "NOT RUN";
                }
                return "FAIL";
            }
            default -> {
                return "NOT RUN";
            }
        }
    }
}
