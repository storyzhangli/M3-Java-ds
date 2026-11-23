package com.zl.d40.experiment;

import com.zl.d40.experiment.LatencyStats;
import com.zl.d40.experiment.RelevanceJudgment;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标计算工具（纯函数，可离线单测）。
 *
 * <p>覆盖 HitRate、micro Precision、噪声率与基于分位数的延迟统计；
 * 分位数采用线性插值法（与 numpy 默认的 linear interpolation 同口径）。</p>
 */
public final class ExperimentMetricsCalculator {

    private ExperimentMetricsCalculator() {
    }

    /**
     * 组级 HitRate：至少命中一个标注片段的题数 / 可判题数。
     */
    public static double hitRate(List<RelevanceJudgment> judgments) {
        if (judgments.isEmpty()) {
            return 0.0;
        }
        long hits = judgments.stream().filter(RelevanceJudgment::hit).count();
        return (double) hits / (double) judgments.size();
    }

    /**
     * 组级 micro Precision：相关返回总数 / 实际返回总数。
     */
    public static double microPrecision(List<RelevanceJudgment> judgments) {
        int relevant = judgments.stream().mapToInt(RelevanceJudgment::relevantCount).sum();
        int returned = judgments.stream().mapToInt(RelevanceJudgment::returnedCount).sum();
        if (returned == 0) {
            return 0.0;
        }
        return (double) relevant / (double) returned;
    }

    /**
     * 组级平均噪声：每题噪声片段数的算术平均。
     */
    public static double averageNoise(List<RelevanceJudgment> judgments) {
        if (judgments.isEmpty()) {
            return 0.0;
        }
        int noise = judgments.stream().mapToInt(RelevanceJudgment::noiseCount).sum();
        return (double) noise / (double) judgments.size();
    }

    /**
     * 组级平均 Recall：单题 Recall 的算术平均。
     */
    public static double averageRecall(List<RelevanceJudgment> judgments) {
        if (judgments.isEmpty()) {
            return 0.0;
        }
        double total = judgments.stream().mapToDouble(RelevanceJudgment::recall).sum();
        return total / (double) judgments.size();
    }

    /**
     * 计算延迟统计（毫秒）。
     */
    public static LatencyStats latencyStats(List<Double> elapsedMillis) {
        if (elapsedMillis == null || elapsedMillis.isEmpty()) {
            return new LatencyStats(0, 0.0, 0.0, 0.0, 0.0);
        }
        List<Double> sorted = new ArrayList<>(elapsedMillis);
        sorted.sort(Double::compare);
        double sum = 0.0;
        for (double value : sorted) {
            sum += value;
        }
        return new LatencyStats(
                sorted.size(),
                sum / sorted.size(),
                percentile(sorted, 50.0),
                percentile(sorted, 95.0),
                sorted.get(sorted.size() - 1));
    }

    /**
     * 线性插值分位数；sorted 已升序，p 为 0..100。
     */
    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = lower + 1;
        if (upper >= sorted.size()) {
            return sorted.get(sorted.size() - 1);
        }
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
