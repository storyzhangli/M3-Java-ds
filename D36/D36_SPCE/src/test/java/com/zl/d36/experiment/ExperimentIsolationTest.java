package com.zl.d36.experiment;

import com.zl.d36.knowledge.KnowledgeChunk;
import com.zl.d36.knowledge.chunking.ChunkingProperties;
import com.zl.d36.knowledge.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前缀 / 命名空间隔离与"不修改生产默认配置"验证。
 */
class ExperimentIsolationTest {

    private List<KnowledgeChunk> sampleChunks() {
        return List.of(
                new KnowledgeChunk("DOC-A", null, "DOC-A-C01", "标题A", "MANUAL",
                        "s1", 1, null, null, "这是第一段内容用于测试", null, null),
                new KnowledgeChunk("DOC-A", null, "DOC-A-C02", "标题A", "MANUAL",
                        "s2", 2, null, null, "这是第二段内容用于测试", null, null));
    }

    @Test
    void prefixesAreDistinct() {
        List<String> prefixes = Arrays.stream(ExperimentGroup.values())
                .map(ExperimentGroup::documentIdPrefix)
                .toList();
        assertThat(prefixes).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    void experimentVersionsAreDistinct() {
        List<String> versions = Arrays.stream(ExperimentGroup.values())
                .map(ExperimentGroup::experimentVersion)
                .toList();
        assertThat(versions).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    void reconstructedDocumentsCarryPrefixAndKeepOriginalId() {
        List<ReconstructedDocument> docs =
                ReconstructedDocument.rebuild(sampleChunks(), ExperimentGroup.G1);
        assertThat(docs).hasSize(1);
        ReconstructedDocument doc = docs.get(0);
        assertThat(doc.source().documentId())
                .startsWith(ExperimentGroup.G1.documentIdPrefix())
                .endsWith("DOC-A");
        assertThat(doc.originalDocumentId()).isEqualTo("DOC-A");
        assertThat(doc.source().version()).isEqualTo(ExperimentGroup.G1.experimentVersion());
    }

    @Test
    void experimentChunkingConfigIsIndependentFromDefaults() {
        ChunkingProperties defaults = new ChunkingProperties();
        int defaultChunkSize = defaults.getChunkSize();
        int defaultOverlap = defaults.getOverlap();

        ChunkingProperties experimentProps =
                ExperimentGroup.G1.toParameters().toChunkingProperties();

        assertThat(experimentProps).isNotSameAs(defaults);
        assertThat(experimentProps.getChunkSize()).isEqualTo(200);
        // 默认配置对象字段保持不变。
        assertThat(defaults.getChunkSize()).isEqualTo(defaultChunkSize);
        assertThat(defaults.getOverlap()).isEqualTo(defaultOverlap);
    }

    @Test
    void experimentRetrievalConfigIsIndependentFromDefaults() {
        RetrievalProperties defaults = new RetrievalProperties();
        int defaultTopK = defaults.getDefaultTopK();

        RetrievalProperties experimentProps =
                ExperimentGroup.G3.toParameters().toRetrievalProperties();

        assertThat(experimentProps).isNotSameAs(defaults);
        assertThat(experimentProps.getDefaultTopK()).isEqualTo(10);
        assertThat(defaults.getDefaultTopK()).isEqualTo(defaultTopK);
    }
}
