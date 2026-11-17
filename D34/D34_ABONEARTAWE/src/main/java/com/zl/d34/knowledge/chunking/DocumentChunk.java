package com.zl.d34.knowledge.chunking;

import com.zl.d34.knowledge.chunking.ChunkBoundaryType;
import com.zl.d34.knowledge.chunking.ChunkSource;
import com.zl.d34.knowledge.chunking.ChunkStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A traceable chunk produced from one document version.
 */
public record DocumentChunk(
        String documentId,
        String title,
        String version,
        int chunkIndex,
        ChunkStrategy strategy,
        String content,
        int characterCount,
        List<Integer> pageNumbers,
        List<ChunkSource> sources,
        ChunkBoundaryType endBoundary,
        String lifecycleStatus,
        LocalDateTime effectiveAt,
        LocalDateTime expiresAt) {

    /**
     * 兼容构造器：无生命周期元数据的历史块，按 ACTIVE 立即生效处理。
     */
    public DocumentChunk(String documentId, String title, String version, int chunkIndex,
                         ChunkStrategy strategy, String content, int characterCount,
                         List<Integer> pageNumbers, List<ChunkSource> sources,
                         ChunkBoundaryType endBoundary) {
        this(documentId, title, version, chunkIndex, strategy, content, characterCount,
                pageNumbers, sources, endBoundary, "ACTIVE", null, null);
    }

    public DocumentChunk {
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
        sources = sources == null ? List.of() : List.copyOf(sources);
        lifecycleStatus = lifecycleStatus == null || lifecycleStatus.isBlank()
                ? "ACTIVE" : lifecycleStatus;
    }
}
