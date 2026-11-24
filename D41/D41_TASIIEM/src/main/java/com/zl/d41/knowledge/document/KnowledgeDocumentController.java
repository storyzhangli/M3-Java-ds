package com.zl.d41.knowledge.document;

import com.zl.d41.access.AccessGuard;
import com.zl.d41.common.Result;
import com.zl.d41.knowledge.document.KnowledgeDocument;
import com.zl.d41.knowledge.document.KnowledgeDocumentService;
import com.zl.d41.knowledge.document.dto.KnowledgeDocumentExtractionView;
import com.zl.d41.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d41.knowledge.document.dto.KnowledgeDocumentView;
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

    /** R01：特权写面审计（随 access.enabled 装配；disabled 缺席=零差异）。 */
    private final ObjectProvider<com.zl.d41.knowledge.sync.AuditService> auditServiceProvider;

    public KnowledgeDocumentController(KnowledgeDocumentService service,
                                       ObjectProvider<AccessGuard> accessGuardProvider,
                                       ObjectProvider<com.zl.d41.knowledge.sync.AuditService>
                                               auditServiceProvider) {
        this.service = service;
        this.accessGuardProvider = accessGuardProvider;
        this.auditServiceProvider = auditServiceProvider;
    }

    @PostMapping
    public Result<KnowledgeDocumentView> register(
            @Valid @RequestBody KnowledgeDocumentRegistrationRequest request) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        com.zl.d41.access.AuthContext actor = guard == null ? null : guard.requireAdmin();
        KnowledgeDocumentView view = service.register(request);
        if (actor != null) {
            auditAllowed(actor, "DOCUMENT_REGISTER", view.documentId(), view.version());
        }
        return Result.success(view);
    }

    @PostMapping(value = "/{documentId}/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentView> importFile(
            @PathVariable String documentId,
            @RequestPart("file") MultipartFile file) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        com.zl.d41.access.AuthContext actor = guard == null ? null : guard.requireAdmin();
        KnowledgeDocumentView view = service.importFile(documentId, file);
        if (actor != null) {
            auditAllowed(actor, "DOCUMENT_IMPORT", documentId, view.version());
        }
        return Result.success(view);
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

    /** 审计落笔（disabled 时 AuditService Bean 缺席=不写，零差异；写失败不阻断）。 */
    private void auditAllowed(com.zl.d41.access.AuthContext actor, String action,
                              String targetDocumentId, String version) {
        com.zl.d41.knowledge.sync.AuditService audit = auditServiceProvider.getIfAvailable();
        if (audit != null && actor != null) {
            audit.recordAllowed(actor, action, targetDocumentId, version);
        }
    }
}
