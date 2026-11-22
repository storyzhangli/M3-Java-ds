package com.zl.d39.rag.model;

import com.zl.d39.rag.model.RagDecision;
import com.zl.d39.rag.model.RagReasonCode;
import com.zl.d39.rag.model.RetrievedChunk;

import java.util.List;

/**
 * 证据评估结果（证据评估门禁的唯一输出契约，禁用无约束 Map）。
 *
 * @param decision             业务决策（ANSWER/CLARIFY/REFUSE/CONFLICT）
 * @param reasonCode           稳定原因码
 * @param reason               可读原因说明（对外可展示）
 * @param evidenceIds          评估输入的候选证据 ID（过滤前）
 * @param acceptedEvidenceIds  通过生命周期/型号/相关性/充分性校验的证据 ID
 * @param rejectedEvidenceIds  被拒绝的证据 ID（按拒绝顺序稳定）
 * @param conflictGroups       冲突分组（仅 decision=CONFLICT 非空）
 * @param missingInformation   缺失的必要条件（仅 decision=CLARIFY 非空）
 * @param assessmentDetails    评估明细（aspect→detail 结构化列表，替代无约束 Map）
 * @param acceptedChunks       通过校验的原始块（供组装阶段直接消费，避免二次过滤）
 * @param sufficiency          充分性等级：DIRECT / LIMITED_INFERENCE / INSUFFICIENT / UNCOVERED
 */
public record EvidenceAssessment(RagDecision decision,
                                 RagReasonCode reasonCode,
                                 String reason,
                                 List<String> evidenceIds,
                                 List<String> acceptedEvidenceIds,
                                 List<String> rejectedEvidenceIds,
                                 List<ConflictGroup> conflictGroups,
                                 List<String> missingInformation,
                                 List<AssessmentDetail> assessmentDetails,
                                 List<RetrievedChunk> acceptedChunks,
                                 String sufficiency) {

    /** 充分性等级常量。 */
    public static final String DIRECT = "DIRECT";
    public static final String LIMITED_INFERENCE = "LIMITED_INFERENCE";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    public static final String UNCOVERED = "UNCOVERED";

    /**
     * 冲突分组：同一事实（参数名）下、来自不同 ACTIVE 证据的矛盾取值。
     *
     * @param topic          冲突主题（提取出的参数名）
     * @param conflictingIds 互相矛盾的证据 ID
     * @param detail         取值明细（如 "2.5MPa vs 3.0MPa"）
     */
    public record ConflictGroup(String topic, List<String> conflictingIds, String detail) {
    }

    /**
     * 评估明细条目（key/value 结构化，替代无约束 Map 作为核心契约）。
     */
    public record AssessmentDetail(String aspect, String detail) {
    }

    /** 便捷判定方法。 */
    public boolean shouldAnswer() {
        return decision == RagDecision.ANSWER && acceptedChunks != null && !acceptedChunks.isEmpty();
    }
}
