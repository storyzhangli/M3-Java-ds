package com.zl.d44.e2e;

import com.zl.d44.e2e.E2eVerdict;

import java.util.List;

/**
 * P4（任务书 §6.4 冻结字段序，10 字段）：bad-case 分类记录。
 * observed/expected/evidencePath 必填；retestStatus 只允许 PASS/FAIL/BLOCKED/NOT RUN 四态。
 *
 * @param caseId          案例号（场景号或 用例号或探针件编号）
 * @param source          来源（S0x / P0x / probe / regression）
 * @param observed        实测现象（如实，不美化）
 * @param expected        冻结预期（引用任务书/探针件原文）
 * @param primaryStage    六阶段主判定（BadCaseClassifier 产出）
 * @param secondaryStages 次级命中阶段（优先级链其余命中项）
 * @param reasonCode      观测到的原因码（实码 verbatim）
 * @param evidencePath    证据路径（必填）
 * @param fixRef          修复引用（fixes/fix-records.md 条目号；未修 null）
 * @param retestStatus    复测终态（E2eVerdict 四态名）
 */
public record BadCaseRecord(
        String caseId,
        String source,
        String observed,
        String expected,
        String primaryStage,
        List<String> secondaryStages,
        String reasonCode,
        String evidencePath,
        String fixRef,
        String retestStatus) {

    public BadCaseRecord {
        if (observed == null || observed.isBlank() || expected == null || expected.isBlank()
                || evidencePath == null || evidencePath.isBlank()) {
            throw new IllegalArgumentException("BadCaseRecord 必填字段缺失（observed/expected/evidencePath）");
        }
        if (retestStatus != null) {
            E2eVerdict.parse(retestStatus);
        }
        secondaryStages = secondaryStages == null ? List.of() : List.copyOf(secondaryStages);
    }
}