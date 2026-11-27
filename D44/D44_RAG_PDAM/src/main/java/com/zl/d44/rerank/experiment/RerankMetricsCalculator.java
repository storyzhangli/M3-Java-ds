package com.zl.d44.rerank.experiment;

import java.util.List;

/** MRR@K 与分位数（线性插值口径与 experiment LatencyStats 一致：rank=(n-1)*q 相邻插值）。 */
public final class RerankMetricsCalculator {

    private RerankMetricsCalculator() {
    }

    /**
     * 单运行 MRR@K：returnedLogicalIds（rank 序、去重）中首个命中 Gold 的位置倒数，
     * 未命中为 0。
     */
    public static double mrrAtK(List<String> expectedLogicalIds,
                                List<String> returnedLogicalIds, int k) {
        int limit = Math.min(k, returnedLogicalIds.size());
        for (int i = 0; i < limit; i++) {
            if (expectedLogicalIds.contains(returnedLogicalIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** 线性插值分位数（0<=q<=1；空集合返回 0）。 */
    public static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        double rank = (sorted.size() - 1) * q;
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double frac = rank - low;
        return sorted.get(low) * (1.0 - frac) + sorted.get(high) * frac;
    }
}