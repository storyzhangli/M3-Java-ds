package com.zl.d43.e2e;

import java.util.List;

/**
 * D43 P4：单场景执行记录（e2e-scenario-records.json 的序列化单元）。
 *
 * @param scenarioId  S01–S12
 * @param verdict     四态：PASS/FAIL/BLOCKED/NOT_RUN
 * @param reasonCode  E2eReasonCode 码名（PASS 时可为 null）
 * @param assertions  逐项断言明细（断言名/结果/观测值），失败定位以此为准
 * @param evidencePath 证据文件相对路径（必填）
 * @param elapsedMs   场景耗时
 */
public record ScenarioRecord(
        String scenarioId,
        E2eVerdict verdict,
        String reasonCode,
        List<AssertionOutcome> assertions,
        String evidencePath,
        long elapsedMs) {

    /** 单条断言结果（name 冻结自 E2eScenario 口径拆分，detail 记录实测观测值）。 */
    public record AssertionOutcome(String name, boolean ok, String detail) {
    }

    public static ScenarioRecord pass(E2eScenario scenario, List<AssertionOutcome> assertions,
                                       String evidencePath, long elapsedMs) {
        return new ScenarioRecord(scenario.scenarioId(), E2eVerdict.PASS, null,
                assertions, evidencePath, elapsedMs);
    }

    public static ScenarioRecord fail(E2eScenario scenario, E2eReasonCode reason,
                                      List<AssertionOutcome> assertions,
                                      String evidencePath, long elapsedMs) {
        return new ScenarioRecord(scenario.scenarioId(), E2eVerdict.FAIL, reason.code(),
                assertions, evidencePath, elapsedMs);
    }

    public static ScenarioRecord blocked(E2eScenario scenario, E2eReasonCode reason, String detail,
                                         String evidencePath) {
        return new ScenarioRecord(scenario.scenarioId(), E2eVerdict.BLOCKED, reason.code(),
                List.of(new AssertionOutcome("precondition", false, detail)), evidencePath, 0);
    }

    public static ScenarioRecord notRun(E2eScenario scenario, E2eReasonCode reason, String detail,
                                         String evidencePath) {
        return new ScenarioRecord(scenario.scenarioId(), E2eVerdict.NOT_RUN, reason.code(),
                List.of(new AssertionOutcome("not-run-rationale", false, detail)), evidencePath, 0);
    }
}