package com.zl.d29.knowledge.embedding;

import com.zl.d29.common.Result;
import com.zl.d29.knowledge.chunking.ChunkStrategy;
import com.zl.d29.knowledge.chunking.KnowledgeDocumentChunkingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only embedding preview endpoints.
 */
@RestController
public class EmbeddingController {

    private final ChunkEmbeddingService embeddingService;

    private final KnowledgeDocumentChunkingService chunkingService;

    public EmbeddingController(ChunkEmbeddingService embeddingService,
                               KnowledgeDocumentChunkingService chunkingService) {
        this.embeddingService = embeddingService;
        this.chunkingService = chunkingService;
    }

    @GetMapping("/api/v1/embeddings/model")
    public Result<EmbeddingModelDescriptor> model() {
        return Result.success(embeddingService.descriptor());
    }

    @GetMapping("/api/v1/knowledge/documents/{documentId}/embeddings")
    public Result<EmbeddingBatchResult> embedDocument(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "STRUCTURE_AWARE") ChunkStrategy strategy) {
        return Result.success(embeddingService.embed(
                chunkingService.preview(documentId, strategy)));
    }
}
