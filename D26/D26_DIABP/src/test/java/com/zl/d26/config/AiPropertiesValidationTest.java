package com.zl.d26.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void mockModeMayStartWithoutApiKey() {
        AiProperties properties = validProperties();
        properties.setMode(AiConstants.MODE_MOCK);
        properties.setApiKey("");

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void unknownModeAndUnsafeBaseUrlShouldBeRejected() {
        AiProperties properties = validProperties();
        properties.setMode("other");
        properties.setBaseUrl("file:///etc/passwd");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getMessage())
                .contains("mode 仅支持 mock 或 deepseek")
                .contains("base-url 必须是带 host 的 http/https URI，且不得包含 user-info");

        properties.setMode(AiConstants.MODE_MOCK);
        properties.setBaseUrl("https://user:password@example.test");
        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getMessage())
                .contains("base-url 必须是带 host 的 http/https URI，且不得包含 user-info");
    }

    @Test
    void modelAndReasonerModelMustBeNonBlank() {
        AiProperties properties = validProperties();
        properties.setModel(" ");
        properties.getThinking().setReasonerModel("");

        assertThat(validator.validate(properties)).hasSize(2);
    }

    private AiProperties validProperties() {
        AiProperties properties = new AiProperties();
        properties.setMode(AiConstants.MODE_MOCK);
        properties.setModel("deepseek-flash");
        properties.setBaseUrl("https://api.deepseek.com");
        properties.getThinking().setReasonerModel("deepseek-reasoner");
        properties.setTemperature(0.7);
        return properties;
    }
}
