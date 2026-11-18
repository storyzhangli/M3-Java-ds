package com.zl.d35.rag.service;

import com.zl.d35.equipment.EquipmentFacts;
import com.zl.d35.rag.CitationTestFixtures;
import com.zl.d35.rag.model.RagReasonCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成后校验 URL 拦截单测（D35-007）：FABRICATED_URL。
 */
class PostGenerationValidatorUrlTest {

    private final PostGenerationValidator validator = new PostGenerationValidator();

    private final EquipmentFacts facts = new EquipmentFacts(
            "R2", null, null, "E50", List.of("主轴异响"), null, null, "已停机", false);

    @ParameterizedTest(name = "答案中出现无来源 URL/本机路径被拦截：{0}")
    @ValueSource(strings = {
            "请访问 http://evil.example.com/rule 查看最新规则。[S1]",
            "操作入口见 https://fake.site/download。[S1]",
            "以 javascript:alert(1) 方式执行。[S1]",
            "数据见 file:///C:/secret.txt。[S1]",
            "本机路径为 C:\\Users\\zl\\secret.txt。[S1]",
            "通过 ftp://10.0.0.1/file 获取。[S1]"
    })
    void fabricatedUrlBlocked(String answer) {
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                answer, List.of(CitationTestFixtures.chunkR2Active()), facts);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(RagReasonCode.FABRICATED_URL);
    }

    @Test
    @DisplayName("无 URL 且事实受支持：校验通过")
    void noUrlPasses() {
        PostGenerationValidator.ValidationOutcome outcome = validator.validate(
                "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]",
                List.of(CitationTestFixtures.chunkR2Active()), facts);
        assertThat(outcome.passed()).isTrue();
    }
}
