package com.zl.d43.rag.model;

import com.zl.d43.rag.model.EvidenceAssessment;

import java.util.List;

/**
 * 响应内嵌的决策明细（只暴露对外可展示的评估信息，不暴露内部判定规则细节）。
 *
 * @param clarificationQuestion 澄清问题（仅 CLARIFY 非空，复用澄清策略原话）
 * @param missingInformation    缺失的必要条件（仅 CLARIFY 非空）
 * @param conflictGroups        冲突分组（仅 CONFLICT 非空）
 * @param reason                决策原因摘要
 * @param sufficiency           充分性等级（DIRECT/LIMITED_INFERENCE/INSUFFICIENT/UNCOVERED）
 */
public record RagDecisionDetail(String clarificationQuestion,
                                List<String> missingInformation,
                                List<EvidenceAssessment.ConflictGroup> conflictGroups,
                                String reason,
                                String sufficiency) {

    public static RagDecisionDetail empty() {
        return new RagDecisionDetail(null, List.of(), List.of(), null, null);
    }
}
