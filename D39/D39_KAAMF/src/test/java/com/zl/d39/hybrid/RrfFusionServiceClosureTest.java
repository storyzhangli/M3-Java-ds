package com.zl.d39.hybrid;

import com.zl.d39.hybrid.keyword.KeywordMatchView;
import com.zl.d39.knowledge.retrieval.dto.RetrievalHitView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合闭包单测（C09 单元层）。
 *
 * <p>闭包性质：融合输出 chunkId 集合 ⊆ 向量路命中 ∪ 关键词路命中——两路各自完成
 * 权限过滤后，融合环节不可能引入任何第三来源 chunk，即"先过滤后融合"结构性成立。
 * 叠加 HybridSearchService 集成层矩阵断言（T7 关键词独占 + T3/T5 hybrid 越权零命中），
 * 构成 C09 双层证据。</p>
 */
class RrfFusionServiceClosureTest {

    private final RrfFusionService fusion = new RrfFusionService();

    private static RetrievalHitView vectorHit(int rank, String chunkId) {
        return new RetrievalHitView(rank, chunkId, "doc-" + chunkId, "1", 0, chunkId,
                List.of(1), "FIXED_LENGTH", "content", 0.9 - rank * 0.1, 0.1,
                "COSINE_SIMILARITY", "L2_DISTANCE");
    }

    private static KeywordMatchView keywordHit(int rank, String chunkId) {
        return new KeywordMatchView(rank, chunkId, "doc-" + chunkId, "1", 0, chunkId,
                List.of(1), null, "FIXED_LENGTH", "content", 12.0 - rank, 200,
                List.of("term"), null, null, null);
    }

    @Test
    @DisplayName("闭包：融合输出 ⊆ 两路并集，origin 标记与排名正确")
    void outputSubsetOfRouteUnion() {
        List<RetrievalHitView> vector = List.of(vectorHit(1, "A"), vectorHit(2, "B"));
        List<KeywordMatchView> keyword = List.of(keywordHit(1, "A"), keywordHit(2, "C"));
        List<FusionTraceEntry> out = fusion.fuse(vector, keyword, 60, 10);

        Set<String> union = Set.of("A", "B", "C");
        Set<String> outIds = out.stream().map(FusionTraceEntry::chunkId).collect(Collectors.toSet());
        assertTrue(union.containsAll(outIds), "融合输出越出两路并集: " + outIds);
        assertEquals(3, out.size());
        // A 双路贡献 → 首位；B/C 单路同分 → chunkId 升序
        assertEquals("A", out.get(0).chunkId());
        assertEquals(FusionTraceEntry.ORIGIN_BOTH, out.get(0).origin());
        assertEquals("B", out.get(1).chunkId());
        assertEquals(FusionTraceEntry.ORIGIN_VECTOR_ONLY, out.get(1).origin());
        assertEquals("C", out.get(2).chunkId());
        assertEquals(FusionTraceEntry.ORIGIN_KEYWORD_ONLY, out.get(2).origin());
        // RRF 数值验证：A = 1/61 + 1/61
        assertEquals(2.0 / 61.0, out.get(0).rrfScore(), 1e-12);
    }

    @Test
    @DisplayName("topK 截断保持闭包且不越序")
    void topKTruncationKeepsClosure() {
        List<RetrievalHitView> vector = List.of(vectorHit(1, "A"), vectorHit(2, "B"), vectorHit(3, "C"));
        List<KeywordMatchView> keyword = List.of(keywordHit(1, "X"));
        List<FusionTraceEntry> out = fusion.fuse(vector, keyword, 60, 2);
        assertEquals(2, out.size());
        // A(双路 1/61+1/61) 第一；第二名 X(关键词 rank1=1/61) > B(向量 rank2=1/62)
        assertEquals("A", out.get(0).chunkId());
        assertEquals("X", out.get(1).chunkId());
        // 闭包性质：截断输出仍 ⊆ 两路并集
        assertTrue(Set.of("A", "B", "C", "X").containsAll(
                out.stream().map(FusionTraceEntry::chunkId).collect(Collectors.toSet())));
    }

    @Test
    @DisplayName("空向量路（越权全滤除场景）：输出仅剩关键词路且闭包成立")
    void emptyVectorRouteDegradesToKeywordOnly() {
        List<FusionTraceEntry> out = fusion.fuse(List.of(), List.of(keywordHit(1, "K")), 60, 5);
        assertEquals(1, out.size());
        assertEquals("K", out.get(0).chunkId());
        assertEquals(FusionTraceEntry.ORIGIN_KEYWORD_ONLY, out.get(0).origin());
        // 两路皆空（权限外全查询）→ 空输出，非降级
        assertTrue(fusion.fuse(List.of(), List.of(), 60, 5).isEmpty());
    }
}
