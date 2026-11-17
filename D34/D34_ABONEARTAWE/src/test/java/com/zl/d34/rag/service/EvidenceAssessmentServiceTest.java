package com.zl.d34.rag.service;

import com.zl.d34.equipment.EquipmentClarificationPolicy;
import com.zl.d34.equipment.EquipmentFacts;
import com.zl.d34.equipment.EquipmentInformationExtractor;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.RagDecision;
import com.zl.d34.rag.model.RagReasonCode;
import com.zl.d34.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据评估门禁单元测试：无证据/低相关/生命周期过滤/型号匹配/澄清/冲突/充分性全分支。
 * 全部离线确定性断言（不调模型、不触发检索）。
 */
class EvidenceAssessmentServiceTest {

    private EvidenceAssessmentService service;

    /** 完整事实：型号 R2、故障码 E50、症状、安全状态齐全。 */
    private final EquipmentFacts fullFacts = new EquipmentFacts(
            "R2", null, "水泵", "E50", List.of("主轴有异响"),
            null, null, "已停机", false);

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.validate();
        service = new EvidenceAssessmentService(new EquipmentClarificationPolicy(), properties);
    }

    @Test
    @DisplayName("空检索 → REFUSE/NO_EVIDENCE")
    void emptyRetrievalRefusesWithNoEvidence() {
        EvidenceAssessment assessment = service.assess(List.of(), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.NO_EVIDENCE);
        assertThat(assessment.acceptedChunks()).isEmpty();
    }

    @Test
    @DisplayName("低相关（分数低于相关性阈值）→ REFUSE/LOW_RELEVANCE")
    void lowScoreRefusesWithLowRelevance() {
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkLowScore()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.LOW_RELEVANCE);
    }

    @Test
    @DisplayName("故障码+设备类型命中 → ANSWER/DIRECT")
    void directSupportAnswers() {
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.sufficiency()).isEqualTo(EvidenceAssessment.DIRECT);
        assertThat(assessment.acceptedEvidenceIds()).containsExactly("c-r2-01");
        assertThat(assessment.shouldAnswer()).isTrue();
    }

    @Test
    @DisplayName("仅症状 2-gram 重合 → ANSWER/LIMITED_INFERENCE")
    void symptomOnlyIsLimitedInference() {
        RetrievedChunk symptomChunk = new RetrievedChunk("c-sym-01", "doc-sym", "V1.0", 0,
                "R2 型通用维护说明", List.of(1), "PARAGRAPH",
                "设备运行中主轴出现异响，建议检查轴承润滑情况。",
                0.60, 0.40, "ACTIVE", null, null);
        EvidenceAssessment assessment = service.assess(List.of(symptomChunk), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.sufficiency()).isEqualTo(EvidenceAssessment.LIMITED_INFERENCE);
    }

    @Test
    @DisplayName("缺设备身份 → CLARIFY/MISSING_REQUIRED_CONTEXT")
    void missingIdentityClarifies() {
        EquipmentFacts noIdentity = new EquipmentFacts(null, null, null, "E50",
                List.of("主轴有异响"), null, null, "已停机", false);
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkR2Active()), noIdentity);
        assertThat(assessment.decision()).isEqualTo(RagDecision.CLARIFY);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.MISSING_REQUIRED_CONTEXT);
        assertThat(assessment.missingInformation()).contains("设备型号或可识别的设备身份");
    }

    @Test
    @DisplayName("ACTIVE 证据同参数不同值 → CONFLICT/EVIDENCE_CONFLICT")
    void conflictingValuesDetectConflict() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Conflict()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.CONFLICT);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.EVIDENCE_CONFLICT);
        assertThat(assessment.conflictGroups()).hasSize(1);
        EvidenceAssessment.ConflictGroup group = assessment.conflictGroups().get(0);
        assertThat(group.topic()).contains("压力阈值");
        assertThat(group.conflictingIds()).containsExactlyInAnyOrder("c-r2-01", "c-r2-02");
    }

    @Test
    @DisplayName("DEPRECATED 证据被过滤，新版正常评估")
    void deprecatedEvidenceIsRejected() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Deprecated()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.rejectedEvidenceIds()).containsExactly("c-r2-03");
        assertThat(assessment.acceptedEvidenceIds()).containsExactly("c-r2-01");
    }

    @Test
    @DisplayName("过滤后无可用证据 → REFUSE/INSUFFICIENT_EVIDENCE")
    void onlyDeprecatedRefuses() {
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkR2Deprecated()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("未生效（effectiveAt 未来）证据被过滤")
    void notYetEffectiveIsRejected() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2NotYetEffective()),
                fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.rejectedEvidenceIds()).containsExactly("c-r2-04");
    }

    @Test
    @DisplayName("其他型号资料被拒绝，不误判冲突")
    void otherModelRejectedWithoutConflict() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR3OtherModel()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.rejectedEvidenceIds()).containsExactly("c-r3-01");
        assertThat(assessment.conflictGroups()).isEmpty();
    }

    @Test
    @DisplayName("全部命中均为其他型号 → REFUSE/INSUFFICIENT_EVIDENCE")
    void onlyOtherModelRefuses() {
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkR3OtherModel()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("分数达标但无事实支持信号 → REFUSE/LOW_RELEVANCE")
    void noFactSignalRefuses() {
        EvidenceAssessment assessment = service.assess(
                List.of(RagTestFixtures.chunkNoSignal()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.LOW_RELEVANCE);
    }

    @Test
    @DisplayName("同参数同值不判冲突（多条一致证据正常通过）")
    void consistentValuesDoNotConflict() {
        RetrievedChunk consistent = new RetrievedChunk("c-r2-05", "doc-r2-b", "V1.0", 0,
                "R2 型水泵复检记录", List.of(1), "PARAGRAPH",
                "R2 型水泵复检：压力阈值应设定为 2.5MPa，E50 表示排气阀堵塞。",
                0.58, 0.42, "ACTIVE", null, null);
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), consistent), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.conflictGroups()).isEmpty();
    }

    @Test
    @DisplayName("提取器实测：完整问题提取出的设备事实与测试期望一致")
    void extractorFactsAlignWithFixture() {
        EquipmentInformationExtractor extractor = new EquipmentInformationExtractor();
        EquipmentFacts facts = extractor.extract(List.of(RagTestFixtures.QUERY_FULL));
        assertThat(facts.deviceModel()).isEqualTo("R2");
        assertThat(facts.faultCode()).isEqualTo("E50");
        assertThat(facts.hasDeviceIdentity()).isTrue();
        assertThat(facts.hasSymptoms()).isTrue();
    }
}
