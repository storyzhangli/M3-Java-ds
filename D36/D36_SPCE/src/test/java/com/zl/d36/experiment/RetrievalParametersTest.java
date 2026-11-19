package com.zl.d36.experiment;

import com.zl.d36.knowledge.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Top-K / 阈值单变量、K 越界与阈值零命中口径。
 */
class RetrievalParametersTest {

    @Test
    void validGroupsBuildRetrievalProperties() {
        for (ExperimentGroup group : ExperimentGroup.values()) {
            RetrievalProperties props = group.toParameters().toRetrievalProperties();
            assertThat(props.getDefaultTopK()).isEqualTo(group.topK());
            assertThat(props.getSimilarityThreshold()).isEqualTo(group.similarityThreshold());
            assertThat(props.getMaxTopK()).isEqualTo(50);
        }
    }

    @Test
    void rejectTopKBelowOne() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 200, 20, 0, 0.0, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void rejectThresholdAboveOne() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 200, 20, 5, 1.2, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void rejectNegativeThreshold() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 200, 20, 5, -0.1, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void rejectNaNThreshold() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 200, 20, 5, Double.NaN, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void g3OnlyChangesTopK() {
        RetrievalExperimentParameters base = ExperimentGroup.G0.toParameters();
        RetrievalExperimentParameters g3 = ExperimentGroup.G3.toParameters();
        assertThat(g3.topK()).isNotEqualTo(base.topK());
        assertThat(g3.chunkSize()).isEqualTo(base.chunkSize());
        assertThat(g3.overlap()).isEqualTo(base.overlap());
        assertThat(g3.similarityThreshold()).isEqualTo(base.similarityThreshold());
    }

    @Test
    void g4OnlyChangesThreshold() {
        RetrievalExperimentParameters base = ExperimentGroup.G0.toParameters();
        RetrievalExperimentParameters g4 = ExperimentGroup.G4.toParameters();
        assertThat(g4.similarityThreshold()).isNotEqualTo(base.similarityThreshold());
        assertThat(g4.chunkSize()).isEqualTo(base.chunkSize());
        assertThat(g4.overlap()).isEqualTo(base.overlap());
        assertThat(g4.topK()).isEqualTo(base.topK());
    }

    @Test
    void zeroReturnIsReportedAsZeroHitsAfterThreshold() {
        RelevanceJudgment judgment = RelevanceJudgment.judge(
                "Q01", List.of("DOC-CNC-COOLING-001-C01"), List.of());
        assertThat(judgment.hit()).isFalse();
        assertThat(judgment.returnedCount()).isZero();
        assertThat(judgment.reasonCode())
                .isEqualTo(ExperimentReasonCode.ZERO_HITS_AFTER_THRESHOLD.code());
        assertThat(judgment.precision()).isZero();
    }
}
