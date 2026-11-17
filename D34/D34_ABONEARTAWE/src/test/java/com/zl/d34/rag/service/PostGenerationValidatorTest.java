package com.zl.d34.rag.service;

import com.zl.d34.equipment.EquipmentFacts;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.model.RagReasonCode;
import com.zl.d34.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成后事实支持校验单元测试：新故障码与数值参数必须能映射到已接受证据。
 */
class PostGenerationValidatorTest {

    private PostGenerationValidator validator;

    private final EquipmentFacts fullFacts = new EquipmentFacts(
            "R2", null, "水泵", "E50", List.of("主轴有异响"),
            null, null, "已停机", false);

    @BeforeEach
    void setUp() {
        validator = new PostGenerationValidator();
    }

    @Test
    @DisplayName("答案出现证据不支持的故障码 → UNSUPPORTED_CLAIM")
    void unsupportedFaultCodeIsRejected() {
        String answer = "排气阀故障，故障码 E7 表示密封失效。[S1]";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(RagReasonCode.UNSUPPORTED_CLAIM);
    }

    @Test
    @DisplayName("答案复述问题自带故障码 → 放行")
    void questionFaultCodeIsAllowed() {
        String answer = "故障码 E50 表示排气阀堵塞，需停机泄压后清洗。[S1]";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("证据中存在的故障码 → 放行")
    void evidenceBackedFaultCodeIsAllowed() {
        String answer = "根据资料，E50 表示排气阀堵塞。[S1]";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("答案凭空给出数值参数 → UNSUPPORTED_CLAIM")
    void unsupportedNumericParamIsRejected() {
        String answer = "建议将压力阈值调整到 5.5MPa。[S1]";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(RagReasonCode.UNSUPPORTED_CLAIM);
    }

    @Test
    @DisplayName("证据中出现的数值参数 → 放行")
    void evidenceBackedNumericParamIsAllowed() {
        String answer = "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("空答案 → GENERATION_VALIDATION_FAILED")
    void blankAnswerIsRejected() {
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                "   ", List.of(RagTestFixtures.chunkR2Active()), fullFacts);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(RagReasonCode.GENERATION_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("无证据可用时答案含任何参数 → 拦截（最严格分支）")
    void noEvidenceMeansAnyParamRejected() {
        String answer = "建议压力设为 1.2MPa。";
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.<RetrievedChunk>of(), fullFacts);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(RagReasonCode.UNSUPPORTED_CLAIM);
    }
}
