package com.zl.d36.experiment;

import com.zl.d36.knowledge.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑片段 ID 与向量命名空间映射、歧义判定。
 */
class ChunkIdMappingTest {

    private List<KnowledgeChunk> corpusChunks() {
        return List.of(
                new KnowledgeChunk("DOC-A", null, "DOC-A-C01", "标题", "MANUAL",
                        "s1", 12, null, null, "这是第一段内容", null, null),
                new KnowledgeChunk("DOC-A", null, "DOC-A-C02", "标题", "MANUAL",
                        "s2", 15, null, null, "这是第二段内容", null, null),
                new KnowledgeChunk("DOC-B", null, "DOC-B-C01", "标题B", "GUIDE",
                        "s3", 6, null, null, "这是第三段内容", null, null));
    }

    @Test
    void rebuildBuildsPageToLogicalIdMapping() {
        List<ReconstructedDocument> docs =
                ReconstructedDocument.rebuild(corpusChunks(), ExperimentGroup.G0);
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).pageToLogicalChunkId())
                .containsEntry(12, "DOC-A-C01")
                .containsEntry(15, "DOC-A-C02");
        assertThat(docs.get(1).pageToLogicalChunkId())
                .containsEntry(6, "DOC-B-C01");
    }

    @Test
    void mapsOrderedPagesToLogicalChunkIds() {
        ReconstructedDocument docA =
                ReconstructedDocument.rebuild(corpusChunks(), ExperimentGroup.G0).get(0);
        assertThat(docA.mapPagesToLogicalChunkIds(List.of(12, 15)))
                .containsExactly("DOC-A-C01", "DOC-A-C02");
    }

    @Test
    void unknownPageThrowsMappingAmbiguous() {
        ReconstructedDocument docA =
                ReconstructedDocument.rebuild(corpusChunks(), ExperimentGroup.G0).get(0);
        assertThatThrownBy(() -> docA.mapPagesToLogicalChunkIds(List.of(99)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code());
    }

    @Test
    void duplicatePageThrowsMappingAmbiguous() {
        List<KnowledgeChunk> duplicate = List.of(
                new KnowledgeChunk("DOC-A", null, "DOC-A-C01", "t", "MANUAL",
                        "s", 5, null, null, "内容甲", null, null),
                new KnowledgeChunk("DOC-A", null, "DOC-A-C02", "t", "MANUAL",
                        "s", 5, null, null, "内容乙", null, null));
        assertThatThrownBy(() -> ReconstructedDocument.rebuild(duplicate, ExperimentGroup.G0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code());
    }

    @Test
    void missingPhysicalPageThrowsMappingAmbiguous() {
        List<KnowledgeChunk> noPage = List.of(
                new KnowledgeChunk("DOC-A", null, "DOC-A-C01", "t", "MANUAL",
                        "s", null, null, null, "内容", null, null));
        assertThatThrownBy(() -> ReconstructedDocument.rebuild(noPage, ExperimentGroup.G0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code());
    }
}
