package com.zl.d40.knowledge.sync;

import com.zl.d40.access.AccessGuard;
import com.zl.d40.access.AuthContext;
import com.zl.d40.common.Result;
import com.zl.d40.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d40.knowledge.vectorindex.service.VectorReconciliationService;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 同步闭环运维入口（D40 新增，H7：全端点 requireAdmin + 审计；
 * disabled 时 guard/audit Bean 缺席=匿名可用+不审计，与 D39 收口面同模式）。
 *
 * <ul>
 *   <li>GET  /api/v1/knowledge/sync/status —— 失败可查询（H8）；</li>
 *   <li>POST /api/v1/knowledge/sync/retry —— 手动重试（恒可用，含 RETRY_EXHAUSTED 重置）；</li>
 *   <li>POST /api/v1/knowledge/sync/consistency —— 一致性核查（dryRun 默认 true 只出报告，
 *       H4 修复走管线）。</li>
 * </ul>
 */
@RestController
public class D40SyncController {

    private final DocumentSyncService syncService;

    private final VectorReconciliationService reconciliationService;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    private final ObjectProvider<AuditService> auditServiceProvider;

    public D40SyncController(DocumentSyncService syncService,
                             VectorReconciliationService reconciliationService,
                             ObjectProvider<AccessGuard> accessGuardProvider,
                             ObjectProvider<AuditService> auditServiceProvider) {
        this.syncService = syncService;
        this.reconciliationService = reconciliationService;
        this.accessGuardProvider = accessGuardProvider;
        this.auditServiceProvider = auditServiceProvider;
    }

    /** 重试请求体：operation=DELETE|UPDATE_CLEANUP；version 空串=文档级。 */
    @Data
    public static class SyncRetryRequest {

        private String documentId;

        private String operation;

        private String version;
    }

    @GetMapping("/api/v1/knowledge/sync/status")
    public Result<List<SyncStatusView>> status(
            @RequestParam(required = false) String documentId,
            @RequestParam(defaultValue = "50") int limit) {
        AuthContext actor = requireAdminAndAudit("SYNC_STATUS_QUERY", documentId, "");
        List<SyncStatusView> views = syncService.recent(documentId, Math.min(limit, 500))
                .stream().map(SyncStatusView::of).toList();
        auditIfPresent(actor, "SYNC_STATUS_QUERY", documentId, "", true);
        return Result.success(views);
    }

    @PostMapping("/api/v1/knowledge/sync/retry")
    public Result<String> retry(@RequestBody SyncRetryRequest request) {
        AuthContext actor = requireAdminAndAudit("SYNC_RETRY",
                request.getDocumentId(), request.getVersion());
        SyncOperation operation = SyncOperation.valueOf(
                request.getOperation().toUpperCase(Locale.ROOT));
        String outcome = syncService.retryOne(request.getDocumentId(), operation,
                request.getVersion() == null ? "" : request.getVersion());
        auditIfPresent(actor, "SYNC_RETRY", request.getDocumentId(),
                request.getVersion(), outcome.startsWith("NO_") == false
                        || "NO_OP_ALREADY_SYNCED".equals(outcome));
        return Result.success(outcome);
    }

    @PostMapping("/api/v1/knowledge/sync/consistency")
    public Result<ReconciliationReportView> consistency(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        AuthContext actor = requireAdminAndAudit("CONSISTENCY_CHECK", "", "");
        ReconciliationReportView report = reconciliationService.reconcile(dryRun);
        auditIfPresent(actor, "CONSISTENCY_CHECK", "", dryRun ? "dryRun" : "compensate", true);
        return Result.success(report);
    }

    /** enabled 时校验 ADMIN（拒绝=4001+AUTH_FORBIDDEN，异常先于审计=拒绝面由异常链留痕）；disabled 返回 null。 */
    private AuthContext requireAdminAndAudit(String action, String target, String version) {
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
            audit.recordFailed(actor, action, target, version, "RETRY_NOT_CONVERGED");
        }
    }
}
