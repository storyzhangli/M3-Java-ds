package com.zl.d44.knowledge.chunking;

import com.zl.d44.access.AccessGuard;
import com.zl.d44.common.Result;
import com.zl.d44.knowledge.chunking.ChunkStrategy;
import com.zl.d44.knowledge.chunking.ChunkingComparison;
import com.zl.d44.knowledge.chunking.ChunkingResult;
import com.zl.d44.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d44.knowledge.document.KnowledgeDocumentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only chunk preview endpoints.
 *
 * <p>preview/compare=文档级范围校验（仅 {@code app.access.enabled=true} 生效，
 * 关闭态 guard bean 缺席=零差异）。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents/{documentId}/chunks")
public class KnowledgeDocumentChunkingController {

    private final KnowledgeDocumentChunkingService service;

    private final KnowledgeDocumentService documentService;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    public KnowledgeDocumentChunkingController(KnowledgeDocumentChunkingService service,
                                               KnowledgeDocumentService documentService,
                                               ObjectProvider<AccessGuard> accessGuardProvider) {
        this.service = service;
        this.documentService = documentService;
        this.accessGuardProvider = accessGuardProvider;
    }

    @GetMapping
    public Result<ChunkingResult> preview(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "FIXED_LENGTH") ChunkStrategy strategy) {
        requireDocumentVisible(documentId);
        return Result.success(service.preview(documentId, strategy));
    }

    @GetMapping("/compare")
    public Result<ChunkingComparison> compare(@PathVariable String documentId) {
        requireDocumentVisible(documentId);
        return Result.success(service.compare(documentId));
    }

    private void requireDocumentVisible(String documentId) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            var document = documentService.documentForScope(documentId);
            guard.requireDocumentVisible(document.getTenantId(),
                    document.getDeptId(), document.getDeviceId());
        }
    }
}
