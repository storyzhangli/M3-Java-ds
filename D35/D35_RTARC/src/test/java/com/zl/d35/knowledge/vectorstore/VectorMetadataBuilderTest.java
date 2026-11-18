package com.zl.d35.knowledge.vectorstore;

import com.zl.d35.knowledge.chunking.ChunkBoundaryType;
import com.zl.d35.knowledge.chunking.ChunkSource;
import com.zl.d35.knowledge.chunking.ChunkStrategy;
import com.zl.d35.knowledge.chunking.DocumentChunk;
import com.zl.d35.knowledge.embedding.EmbeddingModelDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量 metadata 溯源键统一构建单测（D35-008）。
 */
class VectorMetadataBuilderTest {

    private final EmbeddingModelDescriptor descriptor = mock(EmbeddingModelDescriptor.class);

    VectorMetadataBuilderTest() {
        when(descriptor.vectorSpaceId()).thenReturn("local-hash-ngram-v1");
        when(descriptor.modelName()).thenReturn("local-hash-ngram-v1");
    }

    private DocumentChunk chunkWithSources(List<ChunkSource> sources) {
        return new DocumentChunk(
                "doc-r2", "R2 型水泵维护手册", "V2.0", 0, ChunkStrategy.FIXED_LENGTH,
                "主轴异响处理步骤", 8, List.of(15, 16), sources, ChunkBoundaryType.HARD_LIMIT);
    }

    @Test
    @DisplayName("公共溯源键齐全（含段落索引真实透传）")
    void buildTraceWritesCommonKeysAndParagraphIndex() {
        Map<String, Object> metadata = VectorMetadataBuilder.buildTrace(
                chunkWithSources(List.of(new ChunkSource(15, 3), new ChunkSource(16, 4))),
                descriptor);

        assertThat(metadata).containsEntry("documentId", "doc-r2")
                .containsEntry("title", "R2 型水泵维护手册")
                .containsEntry("version", "V2.0")
                .containsEntry("chunkIndex", 0)
                .containsEntry("strategy", "FIXED_LENGTH")
                .containsEntry("pageNumbers", List.of(15, 16))
                .containsEntry("paragraphIndex", 3)
                .containsEntry("characterCount", 8)
                .containsEntry("vectorSpaceId", "local-hash-ngram-v1")
                .containsEntry("modelName", "local-hash-ngram-v1");
    }

    @Test
    @DisplayName("无段落来源：paragraphIndex 如实缺失，不补造")
    void missingParagraphIndexNotInvented() {
        Map<String, Object> metadata = VectorMetadataBuilder.buildTrace(
                chunkWithSources(List.of()), descriptor);
        assertThat(metadata).doesNotContainKey("paragraphIndex");
    }

    @Test
    @DisplayName("段落索引取第一个非空")
    void firstNonEmptyParagraphIndexTaken() {
        Map<String, Object> metadata = VectorMetadataBuilder.buildTrace(
                chunkWithSources(List.of(new ChunkSource(15, null), new ChunkSource(16, 7))),
                descriptor);
        assertThat(metadata).containsEntry("paragraphIndex", 7);
    }
}
