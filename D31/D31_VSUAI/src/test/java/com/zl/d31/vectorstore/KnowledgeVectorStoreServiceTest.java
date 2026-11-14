package com.zl.d31.vectorstore;

import com.zl.d31.knowledge.chunking.ChunkBoundaryType;
import com.zl.d31.knowledge.chunking.ChunkStrategy;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.embedding.EmbeddingProperties;
import com.zl.d31.knowledge.vectorstore.KnowledgeVectorStoreService;
import com.zl.d31.knowledge.vectorstore.VectorSearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * KnowledgeVectorStoreService 单元测试（Mock VectorStore，无真实数据库）。
 */
class KnowledgeVectorStoreServiceTest {

    private EmbeddingModelDescriptor descriptor() {
        return EmbeddingModelDescriptor.from(new EmbeddingProperties());
    }

    private DocumentChunk chunk(String documentId, int index, String content) {
        return new DocumentChunk(documentId, "设备手册-" + documentId, "2026.11",
                index, ChunkStrategy.FIXED_LENGTH, content, content.length(),
                List.of(1), List.of(), ChunkBoundaryType.HARD_LIMIT);
    }

    @Test
    @DisplayName("storeChunks 将 Chunk 转为带 UUID id 与溯源 metadata 的 Document 并写入")
    void storeChunksBuildsTraceableDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeVectorStoreService service =
                new KnowledgeVectorStoreService(vectorStore, descriptor());

        int stored = service.storeChunks(List.of(
                chunk("doc-001", 0, "空气压缩机启动失败，检查电机与压力开关。"),
                chunk("doc-001", 1, "轴承异常噪声需要补充润滑并检查磨损。")));

        assertThat(stored).isEqualTo(2);
        verify(vectorStore).add(anyList());
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(2);
        Document first = documents.get(0);
        assertThat(first.getId()).isNotNull();
        assertThat(UUID.fromString(first.getId())).isNotNull();
        Map<String, Object> metadata = first.getMetadata();
        assertThat(metadata.get("documentId")).isEqualTo("doc-001");
        assertThat(metadata.get("chunkIndex")).isEqualTo(0);
        assertThat(metadata.get("title")).isEqualTo("设备手册-doc-001");
        assertThat(metadata.get("vectorSpaceId")).isNotNull();
        assertThat(String.valueOf(metadata.get("vectorSpaceId"))).isNotBlank();
        assertThat(first.getText()).contains("空气压缩机");
    }

    @Test
    @DisplayName("空 Chunk 列表被拒绝，不触达 VectorStore")
    void storeChunksRejectsEmptyList() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeVectorStoreService service =
                new KnowledgeVectorStoreService(vectorStore, descriptor());

        assertThatThrownBy(() -> service.storeChunks(List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("search 将相似度检索结果映射为 VectorSearchHit")
    void searchMapsHits() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document hit = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("空气压缩机启动失败，检查电机与压力开关。")
                .metadata(Map.of("documentId", "doc-001", "chunkIndex", 0, "title", "设备手册"))
                .score(0.93)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        KnowledgeVectorStoreService service =
                new KnowledgeVectorStoreService(vectorStore, descriptor());

        List<VectorSearchHit> hits = service.search("压缩机无法启动", 3, null);

        assertThat(hits).hasSize(1);
        VectorSearchHit first = hits.get(0);
        assertThat(first.documentId()).isEqualTo("doc-001");
        assertThat(first.chunkIndex()).isZero();
        assertThat(first.score()).isEqualTo(0.93);
        assertThat(first.contentSnippet()).contains("空气压缩机");
    }

    @Test
    @DisplayName("空 Query 被拒绝")
    void searchRejectsBlankQuery() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeVectorStoreService service =
                new KnowledgeVectorStoreService(vectorStore, descriptor());

        assertThatThrownBy(() -> service.search("  ", 3, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Query");
    }
}
