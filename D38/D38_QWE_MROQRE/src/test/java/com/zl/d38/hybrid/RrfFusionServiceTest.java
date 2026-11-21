package com.zl.d38.hybrid;

import com.zl.d38.hybrid.keyword.KeywordMatchView;
import com.zl.d38.knowledge.retrieval.dto.RetrievalHitView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合纯函数夹具（C08）：手算值与实现一致——双路重叠、单路缺席、同分 tie-break、
 * 空路、截断、origin 标注、原始分保留、k 参数敏感性，共 9 用例（≥8 判据）。
 *
 * <p>手算基准（k=60）：rank1 贡献 1/61、rank2 贡献 1/62……双路 rank1 = 2/61。</p>
 */
class RrfFusionServiceTest {

    private final RrfFusionService fusion = new RrfFusionService();

    private static RetrievalHitView vector(int rank, String chunkId, double cosine) {
        return new RetrievalHitView(rank, chunkId, "DOC-X", "v1", rank - 1, "标题",
                List.of(), "FIXED_LENGTH", "内容" + chunkId, cosine, 1 - cosine,
                "COSINE_SIMILARITY", "COSINE_DISTANCE");
    }

    private static KeywordMatchView keyword(int rank, String chunkId, double bm25) {
        return new KeywordMatchView(rank, chunkId, "DOC-X", "v1", rank - 1, "标题",
                List.of(), null, "FIXED_LENGTH", "内容" + chunkId, bm25, 10,
                List.of("w:主轴"), null, null, null);
    }

    @Test
    @DisplayName("1 双路同序：rank1 双方 → d1=2/61 > d2=2/62，BOTH 标注")
    void bothRoutesSameOrder() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "d1", 0.9), vector(2, "d2", 0.8)),
                List.of(keyword(1, "d1", 5.0), keyword(2, "d2", 3.0)), 60, 5);
        assertEquals(2, fused.size());
        assertEquals("d1", fused.get(0).chunkId());
        assertEquals(2 / 61.0, fused.get(0).rrfScore(), 1e-12);
        assertEquals(2 / 62.0, fused.get(1).rrfScore(), 1e-12);
        assertEquals(FusionTraceEntry.ORIGIN_BOTH, fused.get(0).origin());
        assertEquals(1 / 61.0, fused.get(0).vectorContribution(), 1e-12);
        assertEquals(1 / 61.0, fused.get(0).keywordContribution(), 1e-12);
    }

    @Test
    @DisplayName("2 完全不相交：各 1/61 同分 → chunkId 升序 tie-break")
    void disjointTieById() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "d9", 0.9)),
                List.of(keyword(1, "d1", 5.0)), 60, 5);
        assertEquals("d1", fused.get(0).chunkId());
        assertEquals("d9", fused.get(1).chunkId());
        assertEquals(fused.get(0).rrfScore(), fused.get(1).rrfScore(), 1e-12);
    }

    @Test
    @DisplayName("3 交叉排名：v=d2>d1、k=d1>d2 → 总分并列，chunkId 升序")
    void crossedRanksTie() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "d2", 0.9), vector(2, "d1", 0.8)),
                List.of(keyword(1, "d1", 5.0), keyword(2, "d2", 3.0)), 60, 5);
        assertEquals(1 / 61.0 + 1 / 62.0, fused.get(0).rrfScore(), 1e-12);
        assertEquals("d1", fused.get(0).chunkId());
        assertEquals("d2", fused.get(1).chunkId());
    }

    @Test
    @DisplayName("4 单路缺席：关键词空路时向量序保持，贡献 0")
    void keywordRouteEmpty() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "b", 0.9), vector(2, "a", 0.8)), List.of(), 60, 5);
        assertEquals("b", fused.get(0).chunkId());
        assertEquals(FusionTraceEntry.ORIGIN_VECTOR_ONLY, fused.get(0).origin());
        assertEquals(0.0, fused.get(0).keywordContribution());
        assertNull(fused.get(0).keywordBm25());
    }

    @Test
    @DisplayName("5 双路全空：返回空列表（EMPTY 非 FAILED）")
    void bothEmpty() {
        assertTrue(fusion.fuse(List.of(), List.of(), 60, 5).isEmpty());
    }

    @Test
    @DisplayName("6 topK 截断：4 候选截 2，仅保留头两名")
    void topKTruncation() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "d1", 0.9), vector(2, "d2", 0.8),
                        vector(3, "d3", 0.7), vector(4, "d4", 0.6)),
                List.of(), 60, 2);
        assertEquals(2, fused.size());
        assertEquals("d1", fused.get(0).chunkId());
        assertEquals("d2", fused.get(1).chunkId());
    }

    @Test
    @DisplayName("7 KEYWORD_ONLY origin 与余弦缺席标注")
    void keywordOnlyOrigin() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(), List.of(keyword(1, "k1", 7.5)), 60, 5);
        assertEquals(FusionTraceEntry.ORIGIN_KEYWORD_ONLY, fused.get(0).origin());
        assertNull(fused.get(0).vectorSimilarity());
        assertEquals(7.5, fused.get(0).keywordBm25());
        assertEquals(1 / 61.0, fused.get(0).rrfScore(), 1e-12);
    }

    @Test
    @DisplayName("8 原始分保留：向量余弦与 BM25 分不被 RRF 覆盖（分数语义红线）")
    void originalScoresPreserved() {
        List<FusionTraceEntry> fused = fusion.fuse(
                List.of(vector(1, "d1", 0.913)),
                List.of(keyword(2, "d1", 4.2)), 60, 5);
        assertEquals(0.913, fused.get(0).vectorSimilarity(), 1e-12);
        assertEquals(4.2, fused.get(0).keywordBm25(), 1e-12);
        assertEquals(1 / 61.0 + 1 / 62.0, fused.get(0).rrfScore(), 1e-12);
    }

    @Test
    @DisplayName("9 k 参数敏感性：k=20 与 k=60 分值不同、顺序一致（头部权重放大）")
    void rrfKSensitivity() {
        List<FusionTraceEntry> k60 = fusion.fuse(
                List.of(vector(1, "d1", 0.9), vector(2, "d2", 0.8)),
                List.of(keyword(2, "d1", 5.0), keyword(1, "d2", 3.0)), 60, 5);
        List<FusionTraceEntry> k20 = fusion.fuse(
                List.of(vector(1, "d1", 0.9), vector(2, "d2", 0.8)),
                List.of(keyword(2, "d1", 5.0), keyword(1, "d2", 3.0)), 20, 5);
        assertEquals(k60.get(0).chunkId(), k20.get(0).chunkId());
        assertTrue(Math.abs(k20.get(0).rrfScore() - k60.get(0).rrfScore()) > 1e-6,
                "k 参与计算必须生效，禁止硬编码 60");
        assertEquals(1 / 21.0 + 1 / 22.0, k20.get(0).rrfScore(), 1e-12);
    }
}
