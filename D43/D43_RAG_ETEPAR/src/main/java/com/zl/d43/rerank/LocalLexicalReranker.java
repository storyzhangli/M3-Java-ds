package com.zl.d43.rerank;

import com.zl.d43.hybrid.keyword.KeywordTokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 本地确定性重排器（§6.2 冻结口径，零依赖零计费零外呼）。
 *
 * <p>词面口径：KeywordTokenizer("w") 词集交集覆盖率 lexical = |Q_w ∩ D_w| / |Q_w|，
 * |Q_w| = 0 时 lexical 恒 0（不抛异常）。排序三级键：lexical DESC → cosine DESC
 * （null 按 -1）→ originalRank ASC，比较键全为值类型且末位为唯一名次，全序确定，
 * 重复执行结果 bit 级一致。</p>
 */
public final class LocalLexicalReranker implements RerankPort {

    /** 冻结模型名（统计/台账口径，非真实远端模型）。 */
    public static final String MODEL = "lexical-w-v1";

    /** KeywordTokenizer 无状态且线程安全，单实例共享。 */
    private static final KeywordTokenizer W = new KeywordTokenizer("w");

    private record Scored(RerankCandidate candidate, double lexical) {
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public RerankOutcome rerank(RerankRequest request) {
        long t0 = System.nanoTime();
        List<RerankCandidate> cands = request.candidates();
        if (cands.isEmpty()) {
            RerankStageStats stats = new RerankStageStats(elapsedMillis(t0), 0, 0, null, "local", MODEL);
            return new RerankOutcome(List.of(), stats,
                    RerankReasonCode.RERANK_EMPTY_CANDIDATES.code());
        }
        Set<String> qTokens = W.tokenCounts(request.query()).keySet();
        List<Scored> scored = new ArrayList<>(cands.size());
        for (RerankCandidate c : cands) {
            double lexical = 0.0;
            if (!qTokens.isEmpty()) {
                Set<String> dTokens = W.tokenCounts(c.content()).keySet();
                int overlap = 0;
                for (String t : qTokens) {
                    if (dTokens.contains(t)) {
                        overlap++;
                    }
                }
                lexical = (double) overlap / qTokens.size();
            }
            scored.add(new Scored(c, lexical));
        }
        scored.sort(Comparator
                .comparingDouble(Scored::lexical).reversed()
                .thenComparing(s -> s.candidate().cosineScore() == null ? -1.0 : s.candidate().cosineScore(),
                        Comparator.reverseOrder())
                .thenComparingInt(s -> s.candidate().originalRank()));
        int topN = Math.min(request.topN(), scored.size());
        List<RerankedHit> hits = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            Scored s = scored.get(i);
            hits.add(new RerankedHit(s.candidate().chunkId(), i + 1,
                    s.candidate().originalRank(), s.lexical(), s.candidate().cosineScore()));
        }
        RerankStageStats stats = new RerankStageStats(elapsedMillis(t0), cands.size(), topN,
                null, "local", MODEL);
        return new RerankOutcome(hits, stats, null);
    }

    private static long elapsedMillis(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000L;
    }
}