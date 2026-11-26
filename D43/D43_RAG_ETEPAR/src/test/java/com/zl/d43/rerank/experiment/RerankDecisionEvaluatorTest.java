package com.zl.d43.rerank.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G-A/G-B/G-C/G-E 门禁判定夹具（C06/C14 决策逻辑，独立于真实实验）。 */
class RerankDecisionEvaluatorTest {

    private static RerankGroupSummary base() {
        return new RerankGroupSummary("R0", "EXECUTED", 10, 30,
                0.50, 0.60, 2.0, 20.0, 40.0, 0.0, 0, true, null);
    }

    private static RerankGroupSummary arm(String g, double mrr, double hit,
                                          double p95, int fail, boolean divergence) {
        return new RerankGroupSummary(g, "EXECUTED", 10, 30,
                mrr, hit, 1.5, 22.0, p95, 1.0, fail, divergence, null);
    }

    @Test
    @DisplayName("G-A 双指标任一达标 + 其余全过 → ADOPTED_OPTIONAL")
    void adoptedWhenAllGatesPass() {
        var decisions = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R1", 0.56, 0.62, 85.0, 0, true)));
        assertEquals("ADOPTED_OPTIONAL", decisions.get(0).verdict());
        assertTrue(decisions.get(0).gateA());
    }

    @Test
    @DisplayName("G-A 双指标均不足 → NOT_ADOPTED")
    void notAdoptedWhenGainInsufficient() {
        var decisions = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R1", 0.54, 0.65, 45.0, 0, true)));
        assertEquals("NOT_ADOPTED", decisions.get(0).verdict());
        assertEquals(false, decisions.get(0).gateA());
    }

    @Test
    @DisplayName("G-C：本地 P95 增量 60ms > 50ms 预算 → NOT_ADOPTED")
    void notAdoptedWhenLatencyBudgetExceeded() {
        var decisions = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R2", 0.60, 0.75, 100.0, 0, true)));
        assertEquals("NOT_ADOPTED", decisions.get(0).verdict());
    }

    @Test
    @DisplayName("G-B FAIL>0 或 G-E 分歧 → NOT_ADOPTED")
    void notAdoptedWhenFailOrDivergence() {
        var d1 = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R1", 0.60, 0.75, 45.0, 1, true)));
        assertEquals("NOT_ADOPTED", d1.get(0).verdict());
        var d2 = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R1", 0.60, 0.75, 45.0, 0, false)));
        assertEquals("NOT_ADOPTED", d2.get(0).verdict());
    }

    @Test
    @DisplayName("BLOCKED 组自证 BLOCKED，不使他组失格")
    void blockedDoesNotContaminate() {
        var decisions = RerankDecisionEvaluator.evaluate(base(), List.of(
                RerankGroupSummary.blocked(RerankExperimentGroup.R3, "RERANK_REMOTE_DISABLED"),
                arm("R1", 0.60, 0.75, 45.0, 0, true)));
        assertEquals("BLOCKED", decisions.get(0).verdict());
        assertEquals("ADOPTED_OPTIONAL", decisions.get(1).verdict());
    }

    @Test
    @DisplayName("远程组预算 2000ms（R3 非 50ms 口径）")
    void remoteBudget() {
        var decisions = RerankDecisionEvaluator.evaluate(base(), List.of(
                arm("R3", 0.60, 0.75, 1800.0, 0, true)));
        assertEquals("ADOPTED_OPTIONAL", decisions.get(0).verdict());
    }
}