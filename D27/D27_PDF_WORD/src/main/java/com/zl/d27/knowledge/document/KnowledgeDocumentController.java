package com.zl.d27.knowledge.document;

import com.zl.d27.common.Result;
import com.zl.d27.knowledge.document.KnowledgeDocumentService;
import com.zl.d27.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d27.knowledge.document.dto.KnowledgeDocumentView;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
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
}
