package com.zl.d43.knowledge.vectorindex.controller;

import com.zl.d43.access.AccessGuard;
import com.zl.d43.common.Result;
import com.zl.d43.knowledge.document.KnowledgeDocumentService;
import com.zl.d43.knowledge.vectorindex.dto.ChunkIndexView;
import com.zl.d43.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d43.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d43.knowledge.vectorindex.dto.VectorIndexTaskView;
import com.zl.d43.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d43.knowledge.vectorindex.service.VectorReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 向量索引运维入口：建索引、查状态、查 Chunk、删除、恢复与核对。
 *
 * <<p>设计为运维/验收接口而非高频业务读接口；所有写操作幂等，可重复调用。</p>
 *
 * <p>暴露面收口（仅 {@code app.access.enabled=true} 生效，关闭态 guard bean 缺席=零差异）：
 * 全部写操作（建索引/删除/恢复/重试/核对）=ADMIN_ONLY；status/chunks 读接口=文档级范围校验。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class VectorIndexController {

    private final DocumentVectorIndexService indexService;

    private final VectorReconciliationService reconciliationService;

    private final KnowledgeDocumentService documentService;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    /** R01：特权写面审计（随 access.enabled；disabled 缺席零差异，响应契约不变）。 */
    private final ObjectProvider<com.zl.d43.knowledge.sync.AuditService> auditServiceProvider;

    public VectorIndexController(DocumentVectorIndexService indexService,
                                 VectorReconciliationService reconciliationService,
                                 KnowledgeDocumentService documentService,
                                 ObjectProvider<AccessGuard> accessGuardProvider,
                                 ObjectProvider<com.zl.d43.knowledge.sync.AuditService>
                                         auditServiceProvider) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
        this.documentService = documentService;
        this.accessGuardProvider = accessGuardProvider;
        this.auditServiceProvider = auditServiceProvider;
    }

    /**
     * 从知识文档页面重新切分并幂等入库；重复调用同一文档命中幂等短路。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<VectorIndexResultView> index(
            @PathVariable String documentId,
            @RequestParam(required = false) String strategy) {
        requireAdmin("VECTOR_INDEX", documentId, "");
        return Result.success(indexService.indexFromPages(documentId, strategy));
    }

    /**
     * 查询文档全部版本的索引任务状态。
     */
    @GetMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<List<VectorIndexTaskView>> status(@PathVariable String documentId) {
        requireDocumentVisible(documentId);
        return Result.success(indexService.getStatus(documentId));
    }

    /**
     * 查询 Chunk 关联；version 省略时返回全部版本。
     */
    @GetMapping("/api/v1/knowledge/documents/{documentId}/vector-index/chunks")
    public Result<List<ChunkIndexView>> chunks(
            @PathVariable String documentId,
            @RequestParam(required = false) String version) {
        requireDocumentVisible(documentId);
        return Result.success(indexService.listChunks(documentId, version));
    }

    /**
     * 删除向量索引；version 省略时删除该文档全部版本。
     */
    @DeleteMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<Void> delete(
            @PathVariable String documentId,
            @RequestParam(required = false) String version) {
        requireAdmin("VECTOR_DELETE", documentId, version == null ? "ALL" : version);
        if (version == null || version.isBlank()) {
            indexService.deleteByDocument(documentId);
        } else {
            indexService.deleteByDocumentVersion(documentId, version);
        }
        return Result.success(null);
    }

    /**
     * 手动触发卡住任务恢复（基于 Outbox 快照幂等重建）。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index/recover")
    public Result<List<VectorIndexResultView>> recover(@PathVariable String documentId) {
        requireAdmin("VECTOR_RECOVER", documentId, "");
        log.info("手动触发卡住任务恢复 documentId={}", documentId);
        return Result.success(indexService.recoverStuckTasks());
    }

    /**
     * 手动触发失败任务重试。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index/retry")
    public Result<List<VectorIndexResultView>> retry(@PathVariable String documentId) {
        requireAdmin("VECTOR_RETRY_FAILED", documentId, "");
        log.info("手动触发失败任务重试 documentId={}", documentId);
        return Result.success(indexService.retryFailedTasks());
    }

    /**
     * 跨系统一致性核对；dryRun=true 只出报告。
     */
    @PostMapping("/api/v1/knowledge/vector-index/reconcile")
    public Result<ReconciliationReportView> reconcile(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        requireAdmin("VECTOR_RECONCILE", "", "dryRun=" + dryRun);
        return Result.success(reconciliationService.reconcile(dryRun));
    }

    private void requireAdmin(String action, String target, String version) {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            com.zl.d43.access.AuthContext actor = guard.requireAdmin();
            com.zl.d43.knowledge.sync.AuditService audit = auditServiceProvider.getIfAvailable();
            if (audit != null && actor != null) {
                audit.recordAllowed(actor, action, target, version);
            }
        }
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
