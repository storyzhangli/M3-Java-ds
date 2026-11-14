package com.zl.d31.knowledge.chunking;

import com.zl.d31.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only chunk preview endpoints.
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents/{documentId}/chunks")
public class KnowledgeDocumentChunkingController {

    private final KnowledgeDocumentChunkingService service;

    public KnowledgeDocumentChunkingController(KnowledgeDocumentChunkingService service) {
        this.service = service;
    }

    @GetMapping
    public Result<ChunkingResult> preview(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "FIXED_LENGTH") ChunkStrategy strategy) {
        return Result.success(service.preview(documentId, strategy));
    }

    @GetMapping("/compare")
    public Result<ChunkingComparison> compare(@PathVariable String documentId) {
        return Result.success(service.compare(documentId));
    }
}
