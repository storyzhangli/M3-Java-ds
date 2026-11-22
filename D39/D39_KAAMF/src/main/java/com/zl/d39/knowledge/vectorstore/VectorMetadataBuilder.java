package com.zl.d39.knowledge.vectorstore;

import com.zl.d39.knowledge.chunking.ChunkSources;
import com.zl.d39.knowledge.chunking.DocumentChunk;
import com.zl.d39.knowledge.embedding.EmbeddingModelDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量 metadata 溯源键统一构建器：两个写入点（{@code DocumentVectorIndexService} 与
 * {@code KnowledgeVectorStoreService}）共用同一映射方法，保证字段口径一致，禁止只改其一。
 *
 * <p>只负责公共溯源键；写入点各自的扩展键（chunkContentHash/splitVersion/lifecycleStatus 等）
 * 由调用方追加，本类不越界写私有键。</p>
 */
public final class VectorMetadataBuilder {

    private VectorMetadataBuilder() {
    }

    /**
     * 构建公共溯源 metadata（含本次新增的 paragraphIndex，真实非空才写）。
     */
    public static Map<String, Object> buildTrace(DocumentChunk chunk,
                                                 EmbeddingModelDescriptor descriptor) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.documentId());
        if (chunk.title() != null) {
            metadata.put("title", chunk.title());
        }
        metadata.put("version", chunk.version());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("strategy", chunk.strategy() == null ? null : chunk.strategy().name());
        metadata.put("pageNumbers", chunk.pageNumbers());
        Integer paragraphIndex = ChunkSources.firstParagraphIndex(chunk.sources());
        if (paragraphIndex != null) {
            metadata.put("paragraphIndex", paragraphIndex);
        }
        metadata.put("characterCount", chunk.characterCount());
        metadata.put("vectorSpaceId", descriptor.vectorSpaceId());
        metadata.put("modelName", descriptor.modelName());
        return metadata;
    }
}
