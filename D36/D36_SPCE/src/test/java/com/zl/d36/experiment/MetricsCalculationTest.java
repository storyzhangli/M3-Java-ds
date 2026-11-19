package com.zl.d36.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标公式、分位数与 N/A 口径验证。
 */
class MetricsCalculationTest {

    private RelevanceJudgment judgment(String questionId, List<String> expected,
                                       List<String> returned) {
        return RelevanceJudgment.judge(questionId, expected, returned);
    }

    @Test
    void hitRatePrecisionNoiseAndRecallAreCalculated() {
        List<RelevanceJudgment> judgments = List.of(
                judgment("Q1", List.of("A"), List.of("A", "B")),
                judgment("Q2", List.of("C"), List.of("D")));

        assertThat(ExperimentMetricsCalculator.hitRate(judgments))
                .isCloseTo(0.5, within(1e-9));
        // 相关返回总数 1，实际返回总数 3 → 1/3。
        assertThat(ExperimentMetricsCalculator.microPrecision(judgments))
                .isCloseTo(1.0 / 3.0, within(1e-9));
        // 每题噪声 1、1 → 平均 1。
        assertThat(ExperimentMetricsCalculator.averageNoise(judgments))
                .isCloseTo(1.0, within(1e-9));
        // Recall 1、0 → 平均 0.5。
        assertThat(ExperimentMetricsCalculator.averageRecall(judgments))
                .isCloseTo(0.5, within(1e-9));
    }

    @Test
    void allHitsProducePerfectScores() {
        List<RelevanceJudgment> judgments = List.of(
                judgment("Q1", List.of("A"), List.of("A")),
                judgment("Q2", List.of("C"), List.of("C")));
        assertThat(ExperimentMetricsCalculator.hitRate(judgments)).isEqualTo(1.0);
        assertThat(ExperimentMetricsCalculator.microPrecision(judgments)).isEqualTo(1.0);
        assertThat(ExperimentMetricsCalculator.averageNoise(judgments)).isZero();
    }

    @Test
    void latencyPercentilesUseLinearInterpolation() {
        LatencyStats stats = ExperimentMetricsCalculator.latencyStats(
                List.of(10.0, 20.0, 30.0, 40.0, 50.0));
        assertThat(stats.sampleCount()).isEqualTo(5);
        assertThat(stats.avgMs()).isCloseTo(30.0, within(1e-9));
        assertThat(stats.p50Ms()).isCloseTo(30.0, within(1e-9));
        assertThat(stats.maxMs()).isEqualTo(50.0);
        // P95: rank=0.95*4=3.8 → 40 + 0.8*(50-40)=48。
        assertThat(stats.p95Ms()).isCloseTo(48.0, within(1e-9));
    }

    @Test
    void emptyLatencyReturnsZeroedStats() {
        LatencyStats stats = ExperimentMetricsCalculator.latencyStats(List.of());
        assertThat(stats.sampleCount()).isZero();
        assertThat(stats.avgMs()).isZero();
        assertThat(stats.p50Ms()).isZero();
        assertThat(stats.p95Ms()).isZero();
        assertThat(stats.maxMs()).isZero();
    }

    @Test
    void emptyJudgmentsProduceZeroMetrics() {
        List<RelevanceJudgment> empty = List.of();
        assertThat(ExperimentMetricsCalculator.hitRate(empty)).isZero();
        assertThat(ExperimentMetricsCalculator.microPrecision(empty)).isZero();
        assertThat(ExperimentMetricsCalculator.averageNoise(empty)).isZero();
        assertThat(ExperimentMetricsCalculator.averageRecall(empty)).isZero();
    }

    @Test
    void singleQuestionPrecisionAndRecall() {
        RelevanceJudgment j = judgment("Q1", List.of("A"), List.of("A", "B", "C"));
        assertThat(j.precision()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(j.recall()).isEqualTo(1.0);
        assertThat(j.noiseCount()).isEqualTo(2);
        assertThat(j.hit()).isTrue();
    }
}
