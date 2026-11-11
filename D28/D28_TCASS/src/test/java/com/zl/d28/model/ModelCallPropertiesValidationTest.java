package com.zl.d28.model;

import com.zl.d28.config.ModelCallProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCallPropertiesValidationTest {

    @Test
    void defaultsShouldBeWithinStrictBudget() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ModelCallProperties properties = new ModelCallProperties();
            assertThat(factory.getValidator().validate(properties)).isEmpty();
            assertThat(properties.getMaxAttempts()).isEqualTo(2);
            assertThat(properties.getTotalDeadline()).isEqualTo(Duration.ofSeconds(90));
        }
    }

    @Test
    void invalidAttemptsBackoffAndBudgetShouldFailValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            ModelCallProperties attempts = new ModelCallProperties();
            attempts.setMaxAttempts(4);
            assertThat(validator.validate(attempts)).isNotEmpty();

            ModelCallProperties backoff = new ModelCallProperties();
            backoff.setInitialBackoff(Duration.ZERO);
            assertThat(validator.validate(backoff)).isNotEmpty();

            ModelCallProperties budget = new ModelCallProperties();
            budget.setTotalDeadline(Duration.ofSeconds(10));
            assertThat(validator.validate(budget)).isNotEmpty();
        }
    }
}
