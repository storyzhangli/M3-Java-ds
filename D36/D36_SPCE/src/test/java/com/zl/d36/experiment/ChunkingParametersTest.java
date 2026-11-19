package com.zl.d36.experiment;

import com.zl.d36.knowledge.chunking.ChunkingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切分参数合法性与单变量漂移检测。
 */
class ChunkingParametersTest {

    @Test
    void validGroupsBuildChunkingProperties() {
        for (ExperimentGroup group : ExperimentGroup.values()) {
            ChunkingProperties props = group.toParameters().toChunkingProperties();
            assertThat(props.getChunkSize()).isEqualTo(group.chunkSize());
            assertThat(props.getOverlap()).isEqualTo(group.overlap());
            assertThat(props.getMinChunkSize()).isEqualTo(80);
            assertThat(props.getMaxChunkSize()).isEqualTo(720);
        }
    }

    @Test
    void rejectChunkSizeBelowOne() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 0, 10, 5, 0.0, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void rejectNegativeOverlap() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 200, -1, 5, 0.0, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void rejectOverlapNotSmallerThanChunkSize() {
        RetrievalExperimentParameters invalid =
                new RetrievalExperimentParameters("GX", 100, 100, 5, 0.0, "x");
        assertThatThrownBy(invalid::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExperimentReasonCode.CONFIG_INVALID.code());
    }

    @Test
    void g1OnlyChangesChunkSize() {
        RetrievalExperimentParameters base = ExperimentGroup.G0.toParameters();
        RetrievalExperimentParameters g1 = ExperimentGroup.G1.toParameters();
        assertThat(g1.chunkSize()).isNotEqualTo(base.chunkSize());
        assertThat(g1.overlap()).isEqualTo(base.overlap());
        assertThat(g1.topK()).isEqualTo(base.topK());
        assertThat(g1.similarityThreshold()).isEqualTo(base.similarityThreshold());
    }

    @Test
    void g2OnlyChangesOverlap() {
        RetrievalExperimentParameters base = ExperimentGroup.G0.toParameters();
        RetrievalExperimentParameters g2 = ExperimentGroup.G2.toParameters();
        assertThat(g2.overlap()).isNotEqualTo(base.overlap());
        assertThat(g2.chunkSize()).isEqualTo(base.chunkSize());
        assertThat(g2.topK()).isEqualTo(base.topK());
        assertThat(g2.similarityThreshold()).isEqualTo(base.similarityThreshold());
    }
}
