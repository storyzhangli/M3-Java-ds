package com.zl.d30.knowledge.document;

import com.zl.d30.common.Result;
import com.zl.d30.knowledge.document.dto.KnowledgeDocumentExtractionView;
import com.zl.d30.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d30.knowledge.document.dto.KnowledgeDocumentView;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档登记与文件导入入口，只负责协议适配和统一 Result 包装。
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService service;

    public KnowledgeDocumentController(KnowledgeDocumentService service) {
        this.service = service;
    }

    @PostMapping
    public Result<KnowledgeDocumentView> register(
            @Valid @RequestBody KnowledgeDocumentRegistrationRequest request) {
        return Result.success(service.register(request));
    }

    @PostMapping(value = "/{documentId}/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentView> importFile(
            @PathVariable String documentId,
            @RequestPart("file") MultipartFile file) {
        return Result.success(service.importFile(documentId, file));
    }

    @GetMapping("/{documentId}")
    public Result<KnowledgeDocumentView> detail(@PathVariable String documentId) {
        return Result.success(service.detail(documentId));
    }

    @GetMapping("/{documentId}/pages")
    public Result<KnowledgeDocumentExtractionView> pages(@PathVariable String documentId) {
        return Result.success(service.extraction(documentId));
    }
}
