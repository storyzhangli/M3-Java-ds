package com.zl.d38.hybrid;

import com.zl.d38.hybrid.keyword.KeywordMatchView;
import com.zl.d38.knowledge.retrieval.dto.RetrievalHitView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）纯函数服务（D38-R02 P2.1）。
 *
 * <p>公式（Cormack et al., SIGIR 2009，k=60 为原文经验值）：
 * {@code rrfScore(d) = Σ_route 1/(rrfK + rank_route(d))}——rank 从 1 起，
 * 缺席路不贡献，两路等权 1.0，不使用原始分数（天然规避余弦/BM25 量纲不可比问题）。</p>
 *
 * <p>确定性（§3.3）：累加顺序固定为「先向量路、后关键词路」；
 * 输出排序 rrfScore 降序 → 同分 chunkId 升序 → 截取 topK。</p>
 */
@Service
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class RrfFusionService {

    /**
     * 融合结果：截断后的 trace（保序）。chunk 元数据由调用方按 chunkId 回查两路原始命中。
     */
    public List<FusionTraceEntry> fuse(List<RetrievalHitView> vectorHits,
                                       List<KeywordMatchView> keywordHits,
                                       int rrfK,
                                       int topK) {
        Map<String, Partial> partials = new LinkedHashMap<>();
        if (vectorHits != null) {
            for (RetrievalHitView hit : vectorHits) {
                Partial partial = partials.computeIfAbsent(hit.chunkId(), Partial::new);
                partial.vectorRank = hit.rank();
                partial.vectorSimilarity = hit.score();
            }
        }
        if (keywordHits != null) {
            for (KeywordMatchView hit : keywordHits) {
                Partial partial = partials.computeIfAbsent(hit.chunkId(), Partial::new);
                partial.keywordRank = hit.rank();
                partial.keywordBm25 = hit.score();
            }
        }
        List<FusionTraceEntry> entries = new ArrayList<>(partials.size());
        for (Partial partial : partials.values()) {
            // 固定求和顺序：向量贡献在前、关键词贡献在后（浮点累加确定）
            double vectorContribution = partial.vectorRank == null
                    ? 0.0 : 1.0 / (rrfK + partial.vectorRank);
            double keywordContribution = partial.keywordRank == null
                    ? 0.0 : 1.0 / (rrfK + partial.keywordRank);
            double rrf = vectorContribution + keywordContribution;
            String origin;
            if (partial.vectorRank != null && partial.keywordRank != null) {
                origin = FusionTraceEntry.ORIGIN_BOTH;
            } else if (partial.vectorRank != null) {
                origin = FusionTraceEntry.ORIGIN_VECTOR_ONLY;
            } else {
                origin = FusionTraceEntry.ORIGIN_KEYWORD_ONLY;
            }
            entries.add(new FusionTraceEntry(partial.chunkId, origin,
                    partial.vectorRank, partial.vectorSimilarity, vectorContribution,
                    partial.keywordRank, partial.keywordBm25, keywordContribution, rrf));
        }
        entries.sort(Comparator.comparingDouble(FusionTraceEntry::rrfScore).reversed()
                .thenComparing(FusionTraceEntry::chunkId));
        return List.copyOf(entries.subList(0, Math.min(topK, entries.size())));
    }

    private static final class Partial {
        private final String chunkId;
        private Integer vectorRank;
        private Double vectorSimilarity;
        private Integer keywordRank;
        private Double keywordBm25;

        private Partial(String chunkId) {
            this.chunkId = chunkId;
        }
    }
}
