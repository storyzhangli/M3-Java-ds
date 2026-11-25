package com.zl.d42.rerank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zl.d42.rerank.experiment.RerankMetricsCalculator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KP3 冻结夹具 T1–T5 + §6.2 本地口径全序/确定性/空候选语义（C03/C05/C06）。 */
class LocalLexicalRerankerTest {

    private final LocalLexicalReranker reranker = new LocalLexicalReranker();

    private static RerankCandidate cand(String id, String content, int rank, Double cosine) {
        return new RerankCandidate(id, "d42rr-base-" + id, content, rank, cosine);
    }

    @Test
    @DisplayName("T5：同 lexical 同 cosine 按 originalRank 升序（全序锁定）")
    void tieBreakByOriginalRank() {
        var request = new RerankRequest("T5", "!!!", List.of(
                cand("A", "x", 1, 0.7),
                cand("B", "y", 2, 0.7)), 2);
        var outcome = reranker.rerank(request);
        assertNull(outcome.reasonCode());
        assertEquals(List.of("A", "B"), outcome.hits().stream()
                .map(RerankedHit::chunkId).toList());
    }

    @Test
    @DisplayName("lexical 覆盖率：|Q∩D|/|Q|，命中多者优先")
    void lexicalCoverageOrdering() {
        var request = new RerankRequest("L1", "alpha beta gamma", List.of(
                cand("C1", "alpha only", 1, 0.9),
                cand("C2", "alpha beta gamma extra", 2, 0.1),
                cand("C3", "beta gamma", 3, 0.5)), 3);
        var outcome = reranker.rerank(request);
        var hits = outcome.hits();
        assertEquals("C2", hits.get(0).chunkId());
        assertEquals(1.0, hits.get(0).rerankScore(), 1e-9);
        assertEquals("C3", hits.get(1).chunkId());
        assertEquals(2.0 / 3.0, hits.get(1).rerankScore(), 1e-9);
        assertEquals("C1", hits.get(2).chunkId());
        assertEquals(1.0 / 3.0, hits.get(2).rerankScore(), 1e-9);
        assertEquals(List.of(1, 2, 3), hits.stream().map(RerankedHit::newRank).toList());
    }

    @Test
    @DisplayName("|Q|=0 时 lexical 恒 0，退化为 cosine DESC → originalRank ASC")
    void emptyQueryTokensDegradeToCosine() {
        var request = new RerankRequest("L2", "   ", List.of(
                cand("A", "x", 1, 0.1),
                cand("B", "y", 2, 0.9),
                cand("C", "z", 3, null)), 3);
        var hits = reranker.rerank(request).hits();
        assertEquals(List.of("B", "A", "C"), hits.stream()
                .map(RerankedHit::chunkId).toList());
        assertTrue(hits.stream().allMatch(h -> h.rerankScore() == 0.0));
    }

    @Test
    @DisplayName("空候选：EMPTY_CANDIDATES 且不算失败（H4 空结果≠失败）")
    void emptyCandidatesNotFailed() {
        var outcome = reranker.rerank(new RerankRequest("L3", "q", List.of(), 5));
        assertEquals(RerankReasonCode.RERANK_EMPTY_CANDIDATES.code(), outcome.reasonCode());
        assertFalse(outcome.failed());
        assertTrue(outcome.hits().isEmpty());
    }

    @Test
    @DisplayName("topN 截断 + newRank 连续 + provider/model 口径")
    void topNTruncationAndMeta() {
        var request = new RerankRequest("L4", "k", List.of(
                cand("A", "k", 1, 0.1), cand("B", "b", 2, 0.2),
                cand("C", "c", 3, 0.3)), 2);
        var outcome = reranker.rerank(request);
        assertEquals(2, outcome.hits().size());
        assertEquals(List.of(1, 2), outcome.hits().stream()
                .map(RerankedHit::newRank).toList());
        assertEquals("local", outcome.stats().provider());
        assertEquals("lexical-w-v1", outcome.stats().model());
        assertNull(outcome.stats().totalTokens());
        assertEquals("local", reranker.provider());
    }

    @Test
    @DisplayName("确定性：同输入重复执行输出完全一致（G-E 前提）")
    void determinism() {
        var request = new RerankRequest("L5", "alpha beta", List.of(
                cand("A", "alpha", 1, 0.5), cand("B", "beta", 2, 0.5),
                cand("C", "alpha beta", 3, 0.4)), 3);
        var first = reranker.rerank(request);
        var second = reranker.rerank(request);
        assertEquals(first.hits(), second.hits());
    }

    @Test
    @DisplayName("MRR@K 夹具 T1–T4（fixture-truth.json 手算值）")
    void mrrFixtures() {
        assertEquals(0.0, RerankMetricsCalculator.mrrAtK(List.of("X"), List.of("a", "b", "c"), 5), 1e-9);
        assertEquals(0.5, RerankMetricsCalculator.mrrAtK(List.of("Y"), List.of("a", "Y", "c"), 5), 1e-9);
        assertEquals(0.25, RerankMetricsCalculator.mean(List.of(0.0, 0.5)), 1e-9);
        assertEquals(1.0, RerankMetricsCalculator.mrrAtK(List.of("C1"), List.of("C1"), 5), 1e-9);
        assertEquals(0.0, RerankMetricsCalculator.mrrAtK(List.of("C1"), List.of(), 5), 1e-9);
        assertEquals(1.0, RerankMetricsCalculator.mrrAtK(List.of("C2", "C4"), List.of("C2", "n", "C4"), 5), 1e-9);
    }

    @Test
    @DisplayName("分位数：线性插值口径（1..10 → P50=5.5，P95=9.55）")
    void percentileInterpolation() {
        List<Double> v = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            v.add((double) i);
        }
        assertEquals(5.5, RerankMetricsCalculator.percentile(v, 0.50), 1e-9);
        assertEquals(9.55, RerankMetricsCalculator.percentile(v, 0.95), 1e-9);
    }
}