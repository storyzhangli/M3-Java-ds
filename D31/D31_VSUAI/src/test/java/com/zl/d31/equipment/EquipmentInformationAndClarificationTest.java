package com.zl.d31.equipment;

import com.zl.d31.equipment.model.CandidateStatus;
import com.zl.d31.equipment.model.TicketCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备事实提取、澄清优先级和候选投影的离线单元测试。
 */
class EquipmentInformationAndClarificationTest {

    private final EquipmentInformationExtractor extractor = new EquipmentInformationExtractor();
    private final EquipmentClarificationPolicy policy = new EquipmentClarificationPolicy();
    private final EquipmentCandidateExtractor candidateExtractor = new EquipmentCandidateExtractor();

    @Test
    void factsShouldUseOnlyExplicitUserInformation() {
        EquipmentFacts facts = extractor.extract(List.of(
                "数控机床型号 CNC-760，设备编号 A3，报警码 E102，最近运行中异常振动。"));

        assertThat(facts.deviceModel()).isEqualTo("CNC-760");
        assertThat(facts.deviceIdentifier()).isEqualTo("A3");
        assertThat(facts.deviceType()).isEqualTo("数控机床");
        assertThat(facts.faultCode()).isEqualTo("E102");
        assertThat(facts.observedSymptoms()).anyMatch(value -> value.contains("振动"));
    }

    @Test
    void missingModelShouldAskBeforeDiagnosisAndPreserveOptionalCodeSemantics() {
        EquipmentFacts missingModel = extractor.extract(List.of("设备一直异常振动"));
        EquipmentClarificationPolicy.Assessment first = policy.assess(missingModel);
        assertThat(first.required()).isTrue();
        assertThat(first.question()).contains("设备型号");
        assertThat(first.missingInformation()).anyMatch(value -> value.startsWith("故障码（"));

        EquipmentFacts enoughWithoutCode = extractor.extract(
                List.of("设备一直异常振动", "型号是 CNC-760"));
        EquipmentClarificationPolicy.Assessment second = policy.assess(enoughWithoutCode);
        assertThat(second.required()).isFalse();
        assertThat(second.missingInformation()).anyMatch(value -> value.startsWith("故障码（"));
        assertThat(enoughWithoutCode.faultCode()).isNull();
    }

    @Test
    void highRiskAmbiguityShouldRequireSafetyStateAndProduceSafetyCandidate() {
        EquipmentFacts details = extractor.extract(List.of("型号是 CNC-760，设备冒烟并有异响"));
        EquipmentClarificationPolicy.Assessment assessment = policy.assess(details);
        var candidate = candidateExtractor.extract(details, assessment, 1);

        assertThat(assessment.required()).isTrue();
        assertThat(assessment.question()).contains("停机", "断电");
        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.SAFETY_RISK);
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.NEEDS_CLARIFICATION);
        assertThat(candidate.candidateOnly()).isTrue();
    }

    @Test
    void multiTurnFactsShouldAccumulateWithoutRepeatingKnownQuestions() {
        EquipmentFacts facts = extractor.extract(List.of(
                "设备出现异响",
                "型号是 CNC-760",
                "故障码 E211，运行中仍有异响"));
        EquipmentClarificationPolicy.Assessment assessment = policy.assess(facts);
        var candidate = candidateExtractor.extract(facts, assessment, 3);

        assertThat(assessment.required()).isFalse();
        assertThat(candidate.faultCode()).isEqualTo("E211");
        assertThat(candidate.observedSymptoms()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(candidate.observedSymptoms()).noneMatch(value -> value.contains("已确诊"));
        assertThat(candidateExtractor.possibleDirections(facts))
                .allMatch(value -> value.contains("尚未验证"));
    }
}
