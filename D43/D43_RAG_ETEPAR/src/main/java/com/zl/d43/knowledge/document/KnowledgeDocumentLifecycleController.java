package com.zl.d43.knowledge.document;

import com.zl.d43.access.AccessGuard;
import com.zl.d43.access.AuthContext;
import com.zl.d43.common.Result;
import com.zl.d43.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d43.knowledge.sync.AuditService;
import com.zl.d43.knowledge.vectorindex.service.DocumentVectorIndexService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

/**
 * D43 P3（D1 裁决）：文档生命周期 HTTP 写面——补齐任务书 F07 缺口。
 *
 * <p>只编排、不重造：删除=markDeleted（元数据标记）+ DocumentVectorIndexService.deleteByDocument
 * （D40 状态机闭环：markPending→清向量→双端确认→markSynced，PG 失败 FAILED 落库上抛，H8）；
 * 发布新版本=publishNewVersion（内容+指针）+ indexFromPages（新向量先写→SWITCH→UPDATE_CLEANUP）。
 * 权限与 AiSyncController 同构（H2）：AccessGuard/AuditService 经 ObjectProvider，
 * access 关闭态=开发放行零审计（与既有端点一致），开启态=ADMIN-only + DOC_DELETE /
 * DOC_VERSION_PUBLISH 审计落行（H7）。同步终态经 GET /api/v1/knowledge/sync/status 查询，
 * 本视图不重复承载（最小契约面）。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeDocumentLifecycleController {

    static final String ACTION_DELETE = "DOC_DELETE";
    static final String ACTION_PUBLISH = "DOC_VERSION_PUBLISH";

    private final KnowledgeDocumentService documentService;
    private final DocumentVectorIndexService indexService;
    private final ObjectProvider<AccessGuard> accessGuardProvider;
    private final ObjectProvider<AuditService> auditServiceProvider;

    public KnowledgeDocumentLifecycleController(KnowledgeDocumentService documentService,
                                                DocumentVectorIndexService indexService,
                                                ObjectProvider<AccessGuard> accessGuardProvider,
                                                ObjectProvider<AuditService> auditServiceProvider) {
        this.documentService = documentService;
        this.indexService = indexService;
        this.accessGuardProvider = accessGuardProvider;
        this.auditServiceProvider = auditServiceProvider;
    }

    /** 删除编排：元数据标记 → 向量闭环（幂等可重复调用）。 */
    @DeleteMapping("/{documentId}")
    public Result<KnowledgeDocumentView> delete(@PathVariable String documentId) {
        AuthContext actor = requireAdmin(ACTION_DELETE);
        String version = "";
        try {
            KnowledgeDocumentView marked = documentService.markDeleted(documentId);
            version = marked.version();
            indexService.deleteByDocument(documentId.strip());
            auditIfPresent(actor, ACTION_DELETE, documentId, version, true);
            return Result.success(documentService.detail(documentId));
        } catch (RuntimeException ex) {
            auditIfPresent(actor, ACTION_DELETE, documentId, version, false);
            throw ex;
        }
    }

    /** 新版本发布编排：内容+指针 → 新向量先写 → SWITCH → 旧版本 UPDATE_CLEANUP。 */
    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentView> publishVersion(@PathVariable String documentId,
                                                        @RequestPart("file") MultipartFile file) {
        AuthContext actor = requireAdmin(ACTION_PUBLISH);
        String version = "";
        try {
            KnowledgeDocumentView published = documentService.publishNewVersion(documentId, file);
            version = published.version();
            indexService.indexFromPages(documentId, null);
            auditIfPresent(actor, ACTION_PUBLISH, documentId, version, true);
            return Result.success(documentService.detail(documentId));
        } catch (RuntimeException ex) {
            auditIfPresent(actor, ACTION_PUBLISH, documentId, version, false);
            throw ex;
        }
    }

    /** AiSyncController 同构门禁：guard 缺席=开发放行；开启态非 ADMIN 抛 AUTH_FORBIDDEN（4001 通道）。 */
    private AuthContext requireAdmin(String action) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard == null) {
            return null;
        }
        return guard.requireAdmin();
    }

    private void auditIfPresent(AuthContext actor, String action,
                                String target, String version, boolean ok) {
        AuditService audit = auditServiceProvider.getIfAvailable();
        if (audit == null || actor == null) {
            return;
        }
        if (ok) {
            audit.recordAllowed(actor, action, target, version);
        } else {
            audit.recordFailed(actor, action, target, version, "LIFECYCLE_OP_FAILED");
        }
    }
}