package com.zl.d40.knowledge.document;

import com.zl.d40.access.AccessGuard;
import com.zl.d40.common.Result;
import com.zl.d40.knowledge.document.KnowledgeDocument;
import com.zl.d40.knowledge.document.KnowledgeDocumentService;
import com.zl.d40.knowledge.document.dto.KnowledgeDocumentExtractionView;
import com.zl.d40.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d40.knowledge.document.dto.KnowledgeDocumentView;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档登记与文件导入入口，只负责协议适配和统一 Result 包装。
 *
 * <p>暴露面收口（仅 {@code app.access.enabled=true} 生效，关闭态 guard bean 缺席=零差异）：
 * register/import=ADMIN_ONLY；detail/pages=文档级范围校验。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService service;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    public KnowledgeDocumentController(KnowledgeDocumentService service,
                                       ObjectProvider<AccessGuard> accessGuardProvider) {
        this.service = service;
        this.accessGuardProvider = accessGuardProvider;
    }

    @PostMapping
    public Result<KnowledgeDocumentView> register(
            @Valid @RequestBody KnowledgeDocumentRegistrationRequest request) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.requireAdmin();
        }
        return Result.success(service.register(request));
    }

    @PostMapping(value = "/{documentId}/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentView> importFile(
            @PathVariable String documentId,
            @RequestPart("file") MultipartFile file) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.requireAdmin();
        }
        return Result.success(service.importFile(documentId, file));
    }

    @GetMapping("/{documentId}")
    public Result<KnowledgeDocumentView> detail(@PathVariable String documentId) {
        requireDocumentVisible(service.documentForScope(documentId));
        return Result.success(service.detail(documentId));
    }

    @GetMapping("/{documentId}/pages")
    public Result<KnowledgeDocumentExtractionView> pages(@PathVariable String documentId) {
        requireDocumentVisible(service.documentForScope(documentId));
        return Result.success(service.extraction(documentId));
    }

    private void requireDocumentVisible(KnowledgeDocument document) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.requireDocumentVisible(document.getTenantId(),
                    document.getDeptId(), document.getDeviceId());
        }
    }
}
