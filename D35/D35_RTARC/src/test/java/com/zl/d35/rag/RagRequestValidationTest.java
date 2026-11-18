package com.zl.d35.rag;

import com.zl.d35.rag.dto.RagRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 入参校验单测（D35-010）：Bean Validation 注解。
 */
class RagRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("空 query：@NotBlank 拦截")
    void blankQueryRejected() {
        assertThat(validator.validate(new RagRequest("", null, null, null, null))).hasSize(1);
        assertThat(validator.validate(new RagRequest("   ", null, null, null, null))).hasSize(1);
        assertThat(validator.validate(new RagRequest(null, null, null, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("topK 越界：@Min/@Max 拦截")
    void outOfRangeTopKRejected() {
        assertThat(validator.validate(new RagRequest("查询", 0, null, null, null))).hasSize(1);
        assertThat(validator.validate(new RagRequest("查询", 1001, null, null, null))).hasSize(1);
    }

    @Test
    @DisplayName("合法请求：零违规（topK 可空）")
    void validRequestPasses() {
        assertThat(validator.validate(new RagRequest("查询", null, null, null, null))).isEmpty();
        assertThat(validator.validate(new RagRequest("查询", 5, "doc", "V1", "FIXED_LENGTH")))
                .isEmpty();
    }
}
