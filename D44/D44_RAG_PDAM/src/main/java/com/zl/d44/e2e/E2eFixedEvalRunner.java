package com.zl.d44.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.experiment.ExperimentMetricsCalculator;
import com.zl.d44.experiment.RelevanceJudgment;
import com.zl.d44.experiment.ReconstructedDocument;
import com.zl.d44.knowledge.KnowledgeCorpus;
import com.zl.d44.knowledge.chunking.ChunkSourceDocument;
import com.zl.d44.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d44.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d44.knowledge.retrieval.service.SimilarityRetrievalService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P4（e2e-params.retrieval 冻结：topK=5、480/60、threshold 0.0、单轮）：
 * 固定评估集执行器——Q01–Q10 复用主链 SimilarityRetrievalService 直调真实 PG，
 * Gold 判题复用 experiment 包 ReconstructedDocument.mapPagesToLogicalChunkIds
 * （D36/D37/D42 同口径），指标复用 ExperimentMetricsCalculator（在库源码，KP1 退化裁决：
 * 不依赖 D37 评测器 .class HTTP 面）。饱和事实如实注记（D4 裁决，禁调参）。
 */
public class E2eFixedEvalRunner {

    /** 冻结评估口径（e2e-params.retrieval verbatim）。 */
    public static final int TOP_K = 5;
    public static final int REPEAT_RUNS = 1;

    private final SimilarityRetrievalService retrieval;
    private final KnowledgeCorpus corpus;
    private final ObjectMapper objectMapper;

    public E2eFixedEvalRunner(SimilarityRetrievalService retrieval, KnowledgeCorpus corpus,
                              ObjectMapper objectMapper) {
        this.retrieval = retrieval;
        this.corpus = corpus;
        this.objectMapper = objectMapper;
    }

    /** 单题执行记录（fixed-eval-results.json 明细单元）。 */
    public record QuestionOutcome(String questionId, String question,
                                  List<String> expectedLogicalIds, List<String> returnedLogicalIds,
                                  boolean hit, double reciprocalRank, long elapsedMillis) {
    }

    /** 全量评估产出：逐题明细 + 汇总指标 + 饱和注记。 */
    public record EvalResult(List<QuestionOutcome> outcomes, Map<String, Object> metrics,
                             String saturationNote, String engine) {
    }

    /**
     * @param questionsPath questions.jsonl 绝对路径（IT 侧三级路径探测后传入；指纹已由 KP2 冻结复核）
     */
    public EvalResult run(Path questionsPath) throws Exception {
        corpus.load();
        List<ReconstructedDocument> docs = ReconstructedDocument.rebuild(
                corpus.allChunks(), com.zl.d44.experiment.ExperimentGroup.G0);
        Map<String, ReconstructedDocument> byDocId = new LinkedHashMap<>();
        for (ReconstructedDocument doc : docs) {
            ChunkSourceDocument src = doc.source();
            byDocId.put(src.documentId(), doc);
        }

        List<QuestionOutcome> outcomes = new ArrayList<>();
        List<RelevanceJudgment> judgments = new ArrayList<>();
        List<Double> elapsed = new ArrayList<>();
        for (String line : Files.readAllLines(questionsPath)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(line);
            String qid = node.path("id").asText(node.path("questionId").asText());
            String query = node.path("question").asText();
            List<String> expected = new ArrayList<>();
            node.path("expectedChunkIds").forEach(n -> expected.add(n.asText()));
            if (expected.isEmpty()) {
                node.path("expected_chunk_ids").forEach(n -> expected.add(n.asText()));
            }
            long start = System.currentTimeMillis();
            RetrievalResponse resp = retrieval.search(new RetrievalQueryRequest(query, TOP_K, null, null, null));
            long cost = System.currentTimeMillis() - start;
            elapsed.add((double) cost);
            List<String> returned = new ArrayList<>();
            for (var hit : resp.hits()) {
                ReconstructedDocument doc = byDocId.get(hit.documentId());
                if (doc == null || hit.pageNumbers() == null) {
                    continue;
                }
                for (String logical : doc.mapPagesToLogicalChunkIds(hit.pageNumbers())) {
                    if (!returned.contains(logical)) {
                        returned.add(logical);
                    }
                }
            }
            RelevanceJudgment judgment = RelevanceJudgment.judge(qid, expected, returned);
            judgments.add(judgment);
            double rr = 0.0;
            for (int i = 0; i < returned.size(); i++) {
                if (expected.contains(returned.get(i))) {
                    rr = 1.0 / (i + 1);
                    break;
                }
            }
            outcomes.add(new QuestionOutcome(qid, query, expected, returned,
                    judgment.hit(), rr, cost));
        }

        double hitRate = ExperimentMetricsCalculator.hitRate(judgments);
        double mrr = judgments.isEmpty() ? 0.0 : outcomes.stream()
                .mapToDouble(QuestionOutcome::reciprocalRank).average().orElse(0.0);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("questionCount", outcomes.size());
        metrics.put("topK", TOP_K);
        metrics.put("repeat", REPEAT_RUNS);
        metrics.put("hitRateAt5", hitRate);
        metrics.put("mrrAt5", mrr);
        metrics.put("latencyMillis", ExperimentMetricsCalculator.latencyStats(elapsed));
        String saturation = hitRate >= 1.0
                ? "E2E_SATURATION_NOTED：HitRate@5=" + hitRate + "（D42 同值饱和，如实注记，禁调参）"
                : "未饱和（与 D42 基线差异见 badcase 分类）";
        return new EvalResult(List.copyOf(outcomes), Map.copyOf(metrics), saturation,
                "SimilarityRetrievalService 直调（真实 PG，本地哈希 384 维 COSINE 主轨）");
    }
}