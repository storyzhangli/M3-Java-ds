package com.zl.d29.knowledge.chunking;

import com.zl.d29.common.Result;
import com.zl.d29.knowledge.chunking.ChunkStrategy;
import com.zl.d29.knowledge.chunking.ChunkingResult;
import com.zl.d29.knowledge.chunking.KnowledgeDocumentChunkingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
