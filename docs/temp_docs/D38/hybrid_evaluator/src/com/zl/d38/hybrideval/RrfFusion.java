package com.zl.d38.hybrideval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合纯实现（与后端 {@code com.zl.d38.hybrid.RrfFusionService} 同公式，
 * 独立实现用于 SelfTest 手算夹具与交叉核对——两端一致才可信）。
 *
 * <p>{@code rrfScore(d)=Σ 1/(k+rank)}，rank 从 1 起、缺席不贡献、等权；
 * 输出 rrfScore 降序 → 同分 chunkId 升序 → 截取 topK。</p>
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 融合追溯条目（纯函数版）。 */
    public record Trace(String chunkId, String origin, Integer vectorRank, Double vectorScore,
                        double vectorContribution, Integer keywordRank, Double keywordScore,
                        double keywordContribution, double rrfScore) {
    }

    public static List<Trace> fuse(List<String> vectorRanked, List<String> keywordRanked,
                                   Map<String, Double> vectorScores,
                                   Map<String, Double> keywordScores,
                                   int k, int topK) {
        Map<String, int[]> ranks = new LinkedHashMap<>();
        for (int i = 0; i < vectorRanked.size(); i++) {
            ranks.computeIfAbsent(vectorRanked.get(i), x -> new int[]{-1, -1})[0] = i + 1;
        }
        for (int i = 0; i < keywordRanked.size(); i++) {
            ranks.computeIfAbsent(keywordRanked.get(i), x -> new int[]{-1, -1})[1] = i + 1;
        }
        List<Trace> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : ranks.entrySet()) {
            int vr = e.getValue()[0];
            int kr = e.getValue()[1];
            double vc = vr < 0 ? 0.0 : 1.0 / (k + vr);
            double kc = kr < 0 ? 0.0 : 1.0 / (k + kr);
            String origin = vr >= 0 && kr >= 0 ? "BOTH" : vr >= 0 ? "VECTOR_ONLY" : "KEYWORD_ONLY";
            out.add(new Trace(e.getKey(), origin, vr < 0 ? null : vr,
                    vectorScores.get(e.getKey()), vc, kr < 0 ? null : kr,
                    keywordScores.get(e.getKey()), kc, vc + kc));
        }
        out.sort(Comparator.comparingDouble(Trace::rrfScore).reversed()
                .thenComparing(Trace::chunkId));
        return List.copyOf(out.subList(0, Math.min(topK, out.size())));
    }
}
