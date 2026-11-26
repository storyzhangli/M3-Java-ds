package com.zl.d43.rerank;

/**
 * 单阶段执行统计（H9：不落任何全文，仅计数/耗时/用量）。
 *
 * @param totalTokens 本地路径恒 null（零计费）；远程=usage.total_tokens 或 null（缺失如实）
 */
public record RerankStageStats(long elapsedMillis, int inputCount, int outputCount,
                               Integer totalTokens, String provider, String model) {
}