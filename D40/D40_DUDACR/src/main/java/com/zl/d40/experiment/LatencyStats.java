package com.zl.d40.experiment;

/**
 * 延迟统计结果（毫秒）。
 *
 * @param sampleCount 样本数
 * @param avgMs       平均延迟
 * @param p50Ms       中位数（P50）
 * @param p95Ms       95 分位（P95）
 * @param maxMs       最大延迟
 */
public record LatencyStats(int sampleCount,
                           double avgMs,
                           double p50Ms,
                           double p95Ms,
                           double maxMs) {
}
