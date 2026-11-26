package com.zl.d43.rerank.experiment;

import com.zl.d43.rerank.experiment.RerankExperimentGroup;
import com.zl.d43.rerank.experiment.RerankGroupSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * 预冻结门禁 G-A / G-B / G-C / G-E 判定（§门禁口径；G-D 费用由台账单独评估，本类不涉及）。
 *
 * <p>规则：BLOCKED 组仅自证不可 ADOPTED，不使他组失格；G-A 双指标任一达标即过；
 * 全部门禁通过才 ADOPTED_OPTIONAL。</p>
 */
public final class RerankDecisionEvaluator {

    /** 本地 P95 增量上限（ms，冻结）。 */
    public static final double LOCAL_P95_BUDGET_MS = 50.0;
    /** 远程 P95 增量上限（ms，冻结）。 */
    public static final double REMOTE_P95_BUDGET_MS = 2000.0;
    /** G-A：ΔMRR@5 下限（冻结）。 */
    public static final double MRR_GAIN_MIN = 0.05;
    /** G-A：ΔHitRate@5 下限（冻结）。 */
    public static final double HIT_GAIN_MIN = 0.10;

    private RerankDecisionEvaluator() {
    }

    public static List<RerankDecisionRecord> evaluate(RerankGroupSummary baseline,
                                                      List<RerankGroupSummary> arms) {
        List<RerankDecisionRecord> out = new ArrayList<>();
        for (RerankGroupSummary arm : arms) {
            out.add(evaluateOne(baseline, arm));
        }
        return List.copyOf(out);
    }

    private static RerankDecisionRecord evaluateOne(RerankGroupSummary baseline,
                                                    RerankGroupSummary arm) {
        if ("BLOCKED".equals(arm.status())) {
            return new RerankDecisionRecord(arm.group(), "BLOCKED",
                    false, false, false, false, 0.0, 0.0, 0.0,
                    "组被阻断: " + arm.blockedReason() + "（不使他组失格）");
        }
        double dMrr = arm.mrrAtK() - baseline.mrrAtK();
        double dHit = arm.hitRateAtK() - baseline.hitRateAtK();
        double p95Delta = arm.p95TotalMs() - baseline.p95TotalMs();
        boolean gateA = dMrr >= MRR_GAIN_MIN || dHit >= HIT_GAIN_MIN;
        boolean gateB = arm.failCount() == 0;
        boolean isRemote = RerankExperimentGroup.R3.name().equals(arm.group());
        double budget = isRemote ? REMOTE_P95_BUDGET_MS : LOCAL_P95_BUDGET_MS;
        boolean gateC = p95Delta <= budget;
        boolean gateE = arm.repeatDivergenceFree();
        String verdict = (gateA && gateB && gateC && gateE)
                ? "ADOPTED_OPTIONAL" : "NOT_ADOPTED";
        String basis = String.format(
                "dMRR5=%.4f(>=%.2f) dHit5=%.4f(>=%.2f) P95增量=%.1fms(<=%.0f) FAIL=%d 零分歧=%s",
                dMrr, MRR_GAIN_MIN, dHit, HIT_GAIN_MIN, p95Delta, budget,
                arm.failCount(), arm.repeatDivergenceFree());
        return new RerankDecisionRecord(arm.group(), verdict,
                gateA, gateB, gateC, gateE, dMrr, dHit, p95Delta, basis);
    }
}