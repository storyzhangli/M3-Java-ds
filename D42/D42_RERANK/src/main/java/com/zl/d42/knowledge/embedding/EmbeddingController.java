package com.zl.d42.knowledge.embedding;

import com.zl.d42.access.AccessGuard;
import com.zl.d42.common.Result;
import com.zl.d42.knowledge.chunking.ChunkStrategy;
import com.zl.d42.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d42.knowledge.document.KnowledgeDocumentService;
import com.zl.d42.knowledge.embedding.ChunkEmbeddingService;
import com.zl.d42.knowledge.embedding.EmbeddingBatchResult;
import com.zl.d42.knowledge.embedding.EmbeddingModelDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only embedding preview endpoints.
 *
 * <p>embedDocument（会触发真实 embedding 消耗且回显向量内容）=文档级范围校验；
 * model=OUT_OF_SCOPE 不变。关闭态 guard bean 缺席=零差异。</p>
 */
@RestController
public class EmbeddingController {

    private final ChunkEmbeddingService embeddingService;

    private final KnowledgeDocumentChunkingService chunkingService;

    private final KnowledgeDocumentService documentService;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    public EmbeddingController(ChunkEmbeddingService embeddingService,
                               KnowledgeDocumentChunkingService chunkingService,
                               KnowledgeDocumentService documentService,
                               ObjectProvider<AccessGuard> accessGuardProvider) {
        this.embeddingService = embeddingService;
        this.chunkingService = chunkingService;
        this.documentService = documentService;
        this.accessGuardProvider = accessGuardProvider;
    }

    @GetMapping("/api/v1/embeddings/model")
    public Result<EmbeddingModelDescriptor> model() {
        return Result.success(embeddingService.descriptor());
    }

    @GetMapping("/api/v1/knowledge/documents/{documentId}/embeddings")
    public Result<EmbeddingBatchResult> embedDocument(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "STRUCTURE_AWARE") ChunkStrategy strategy) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            var document = documentService.documentForScope(documentId);
            guard.requireDocumentVisible(document.getTenantId(),
                    document.getDeptId(), document.getDeviceId());
        }
        return Result.success(embeddingService.embed(
                chunkingService.preview(documentId, strategy)));
    }
}
