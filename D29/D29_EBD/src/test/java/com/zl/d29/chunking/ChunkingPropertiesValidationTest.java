package com.zl.d29.chunking;

import com.zl.d29.knowledge.chunking.ChunkingProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkingPropertiesValidationTest {

    @Test
    void defaultsShouldBeValid() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ChunkingProperties properties = new ChunkingProperties();

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void overlapGreaterThanOrEqualToChunkSizeShouldBeRejected() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setChunkSize(100);
        properties.setMinChunkSize(20);
        properties.setMaxChunkSize(120);
        properties.setOverlap(100);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(properties)).isNotEmpty();
        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void sizesOutsideConfiguredBoundsShouldBeRejected() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setChunkSize(200);
        properties.setMaxChunkSize(100);
        properties.setMinChunkSize(50);
        properties.setOverlap(10);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(properties)).isNotEmpty();
        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-chunk-size");
    }
}
