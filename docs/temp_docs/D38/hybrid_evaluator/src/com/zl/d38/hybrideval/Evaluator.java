package com.zl.d38.hybrideval;

import com.zl.d38.hybrideval.StrategyBackend.RagResult;
import com.zl.d38.hybrideval.StrategyBackend.RetrievalResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单题评测与判题（复制自 D37 Evaluator：判题基准、9 类失败标签、四态结论逻辑零改动，
 * 保证 A/B 两组评测口径与 D37 冻结基线完全一致；仅 RawRecord 扩展策略与混合观测字段）。
 */
public final class Evaluator {

    /** 原始记录（每题每轮每策略一条）。D37 字段语义零改动，尾部为 R02 扩展。 */
    public record RawRecord(
            String questionId,
            String scenario,
            int round,
            String strategy,
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
            List<String> hitDocumentIds,
            List<String> hitLogicalChunkIds,
            List<String> hitChunkIds,
            List<Double> hitScores,
            List<String> expectedDocumentIds,
            List<String> expectedChunkIds,
            String verdict,
            long keywordSearchMs,
            long fusionMs,
            List<String> fusionTraceDigest,
            boolean requestError,
            boolean requestTimeout) {

        /**
         * 质量投影（P4.2）：命中/相关/标签/结论 + 排序顺序（chunkId 与逻辑片段顺序），
         * 排除一切浮点与计时字段——两次独立运行投影必须逐字节一致。
         */
        public String qualityProjection() {
            StringBuilder sb = new StringBuilder();
            sb.append(questionId).append('\u0001')
                    .append(scenario).append('\u0001')
                    .append(round).append('\u0001')
                    .append(strategy).append('\u0001')
                    .append(hitCount).append('\u0001')
                    .append(returnedDocumentCount).append('\u0001')
                    .append(returnedChunkCount).append('\u0001')
                    .append(relevantDocumentCount).append('\u0001')
                    .append(relevantChunkCount).append('\u0001')
                    .append(noiseCount).append('\u0001')
                    .append(documentHit).append('\u0001')
                    .append(chunkHit).append('\u0001')
                    .append(expectedDecision).append('\u0001')
                    .append(actualDecision).append('\u0001')
                    .append(actualStatus).append('\u0001')
                    .append(reasonCode).append('\u0001')
                    .append(String.join(";", labels)).append('\u0001')
                    .append(String.join(";", hitDocumentIds)).append('\u0001')
                    .append(String.join(";", hitLogicalChunkIds)).append('\u0001')
                    .append(String.join(";", hitChunkIds)).append('\u0001')
                    .append(String.join(";", expectedDocumentIds)).append('\u0001')
                    .append(String.join(";", expectedChunkIds)).append('\u0001')
                    .append(verdict).append('\u0001')
                    .append(String.join(";", fusionTraceDigest)).append('\u0001')
                    .append(requestError).append('\u0001')
                    .append(requestTimeout);
            return sb.toString();
        }
    }

    /** 单题聚合结果（同策略多轮）。 */
    public record QuestionOutcome(EvalQuestion question, List<RawRecord> rounds) {
    }

    private static final Set<String> CONTROLLED_DECISIONS =
            Set.of("CLARIFY", "REFUSE", "CONFLICT");

    /**
     * 执行一轮「检索 + RAG」判题（策略端点）。
     */
    public RawRecord evaluate(StrategyBackend backend, EvalQuestion question,
                              int round, int topK, String strategy) {
        long totalStart = System.nanoTime();
        RetrievalResult retrieval = backend.search(question.question(), topK, strategy);
        RagResult rag = backend.answer(question.question(), topK, strategy);
        long totalElapsed = (System.nanoTime() - totalStart) / 1_000_000L;
        return buildRecord(question, round, strategy, retrieval, rag, totalElapsed);
    }

    private RawRecord buildRecord(EvalQuestion question, int round, String strategy,
                                  RetrievalResult retrieval, RagResult rag,
                                  long totalElapsedMillis) {
        List<Hit> hits = retrieval.hits();
        Set<String> hitDocuments = new LinkedHashSet<>();
        Set<String> hitChunks = new LinkedHashSet<>();
        List<Double> scores = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (Hit hit : hits) {
            chunkIds.add(hit.chunkId());
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

        boolean documentHit = !expectedDocSet.isEmpty() && relevantDocuments >= 1;
        boolean chunkHit = !expectedChunkSet.isEmpty() && relevantChunks >= 1;

        int returnedChunkCount = hitChunks.size();
        int returnedDocumentCount = hitDocuments.size();
        int noiseCount = returnedChunkCount - relevantChunks;
        if (expectedChunkSet.isEmpty()) {
            noiseCount = returnedDocumentCount - relevantDocuments;
        }

        String actualStatus = rag.httpOk() ? rag.status() : null;
        String actualDecision = rag.httpOk() ? rag.decision() : null;
        String reasonCode = rag.httpOk() ? rag.reasonCode() : null;
        Boolean mocked = rag.httpOk() ? rag.mocked() : null;

        List<String> labels = label(question, hits, expectedDocSet, expectedChunkSet,
                documentHit, chunkHit, relevantChunks, returnedChunkCount, noiseCount,
                actualDecision, actualStatus, reasonCode, mocked, retrieval, strategy);

        String verdict = verdict(question, documentHit, chunkHit, actualDecision);

        return new RawRecord(
                question.questionId(), question.scenario(), round, strategy,
                hits.size(), returnedDocumentCount, returnedChunkCount,
                relevantDocuments, relevantChunks, noiseCount,
                documentHit, chunkHit,
                question.expectedDecision(), actualDecision, actualStatus, reasonCode,
                List.copyOf(labels),
                retrieval.queryEmbeddingMs(), retrieval.retrievalElapsedMillis(),
                totalElapsedMillis,
                List.copyOf(hitDocuments), List.copyOf(hitChunks), chunkIds, scores,
                List.copyOf(expectedDocs), List.copyOf(expectedChunks), verdict,
                retrieval.keywordSearchMs(), retrieval.fusionMs(),
                retrieval.fusionTraceDigest(),
                !retrieval.httpOk() || !rag.httpOk(),
                retrieval.timedOut() || rag.timedOut());
    }

    /**
     * 失败标签（9 类一阶 + 细分原因码，与 D37 label() 逐分支一致；
     * 两处口径修正：①HTTP 层失败/超时 → RETRIEVAL_BACKEND_FAIL / RETRIEVAL_TIMEOUT，
     * 请求错误如实计不掩盖；②MISS_RECALL_LOW_SCORE 的 0.15 阈值以余弦为量纲，
     * 仅对 PURE_VECTOR 适用——HYBRID_RRF 命中分数为 RRF 值（~0.016-0.033），
     * 与余弦不可比，误标 LOW_SCORE 属量纲失真，统一归 FILTERED（方法学修正，文档标注）。
     */
    private List<String> label(EvalQuestion question, List<Hit> hits,
                               Set<String> expectedDocs, Set<String> expectedChunks,
                               boolean documentHit, boolean chunkHit,
                               int relevantChunks, int returnedChunks, int noiseCount,
                               String actualDecision, String actualStatus,
                               String reasonCode, Boolean mocked,
                               RetrievalResult retrieval, String strategy) {
        List<String> labels = new ArrayList<>();

        boolean hasExpectation = !expectedDocs.isEmpty() || !expectedChunks.isEmpty();
        if (hasExpectation && !documentHit && !chunkHit) {
            if (hits.isEmpty()) {
                labels.add("MISS_RECALL_EMPTY");
            } else if ("PURE_VECTOR".equals(strategy)
                    && hits.stream().allMatch(h -> h.score() != null && h.score() < 0.15)) {
                labels.add("MISS_RECALL_LOW_SCORE");
            } else {
                labels.add("MISS_RECALL_FILTERED");
            }
        }

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

        if (Boolean.TRUE.equals(mocked)) {
            // mock 不回填真实引用，不判伪造（D37 规则沿用）
        } else if (mocked != null && !mocked) {
            if ("FABRICATED_URL".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_FABRICATED_URL");
            } else if ("UNSUPPORTED_CLAIM".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_UNSUPPORTED");
            } else if ("INVALID_CITATION".equals(reasonCode)) {
                labels.add("FABRICATED_CITATION_UNKNOWN_ID");
            }
        }

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

        if (actualDecision != null) {
            boolean expectedClarify = "CLARIFY".equals(question.expectedDecision());
            boolean actualClarify = "CLARIFY".equals(actualDecision);
            if (expectedClarify && !actualClarify) {
                labels.add("CLARIFY_EXPECTED_MISSING");
            } else if (!expectedClarify && actualClarify) {
                labels.add("CLARIFY_UNEXPECTED");
            }
        }

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
        if (!retrieval.httpOk()) {
            labels.add(retrieval.timedOut() ? "RETRIEVAL_TIMEOUT" : "RETRIEVAL_BACKEND_FAIL");
        }

        return dedupe(labels);
    }

    private List<String> dedupe(List<String> labels) {
        Set<String> set = new LinkedHashSet<>(labels);
        return List.copyOf(set);
    }

    /** 四态结论（与 D37 verdict() 逐分支一致）。 */
    private String verdict(EvalQuestion question, boolean documentHit, boolean chunkHit,
                           String actualDecision) {
        String scenario = question.scenario();
        boolean hit = documentHit || chunkHit;
        switch (scenario) {
            case "ANSWERABLE", "TERMINOLOGY" -> {
                if (!hit) {
                    return "FAIL";
                }
                if (actualDecision != null && !"ANSWER".equals(actualDecision)) {
                    return "FAIL";
                }
                return "PASS";
            }
            case "UNANSWERABLE" -> {
                if (hit) {
                    return "FAIL";
                }
                if (actualDecision != null && !CONTROLLED_DECISIONS.contains(actualDecision)) {
                    return "FAIL";
                }
                return "PASS";
            }
            case "BOUNDARY" -> {
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
