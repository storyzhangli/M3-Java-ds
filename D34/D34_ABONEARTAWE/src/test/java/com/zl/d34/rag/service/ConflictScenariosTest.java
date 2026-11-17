package com.zl.d34.rag.service;

import com.zl.d34.equipment.EquipmentClarificationPolicy;
import com.zl.d34.equipment.EquipmentFacts;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.RagDecision;
import com.zl.d34.rag.model.RagReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突专项测试（任务书四项）：
 * CON-01 同参数不同值 → 冲突；CON-02 同参数同值 → 不冲突；
 * CON-03 不同设备型号 → 拒绝不误判冲突；CON-04 旧版废弃 + 新版生效 → 过滤旧版后不冲突。
 */
class ConflictScenariosTest {

    private EvidenceAssessmentService service;

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
    @DisplayName("CON-01 同参数不同值 → CONFLICT，冲突组含双方证据 ID 与取值")
    void con01ConflictingValues() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Conflict()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.CONFLICT);
        assertThat(assessment.reasonCode()).isEqualTo(RagReasonCode.EVIDENCE_CONFLICT);
        EvidenceAssessment.ConflictGroup group = assessment.conflictGroups().get(0);
        assertThat(group.topic()).contains("压力阈值");
        assertThat(group.detail()).contains("2.5").contains("3.0");
        assertThat(group.conflictingIds()).containsExactlyInAnyOrder("c-r2-01", "c-r2-02");
    }

    @Test
    @DisplayName("CON-02 同参数同值 → 不冲突，正常评估")
    void con02ConsistentValues() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.conflictGroups()).isEmpty();
    }

    @Test
    @DisplayName("CON-03 不同设备型号 → 拒绝该资料且不误判冲突")
    void con03OtherModelRejectedNotConflict() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR3OtherModel()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.rejectedEvidenceIds()).containsExactly("c-r3-01");
        assertThat(assessment.conflictGroups()).isEmpty();
    }

    @Test
    @DisplayName("CON-04 旧版 DEPRECATED + 新版 ACTIVE 矛盾 → 过滤旧版后不冲突")
    void con04DeprecatedVersionFiltered() {
        EvidenceAssessment assessment = service.assess(List.of(
                RagTestFixtures.chunkR2Deprecated(), RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(assessment.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(assessment.rejectedEvidenceIds()).containsExactly("c-r2-03");
        assertThat(assessment.conflictGroups()).isEmpty();
        assertThat(assessment.acceptedEvidenceIds()).containsExactly("c-r2-01");
    }
}
