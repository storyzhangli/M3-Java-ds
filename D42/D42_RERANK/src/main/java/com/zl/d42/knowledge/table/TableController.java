package com.zl.d42.knowledge.table;

import com.zl.d42.access.AccessGuard;
import com.zl.d42.access.AuthContext;
import com.zl.d42.common.Result;
import com.zl.d42.common.ResultCode;
import com.zl.d42.knowledge.document.KnowledgeDocument;
import com.zl.d42.knowledge.document.KnowledgeDocumentException;
import com.zl.d42.knowledge.document.KnowledgeDocumentService;
import com.zl.d42.knowledge.retrieval.RetrievalException;
import com.zl.d42.knowledge.sync.AuditService;
import com.zl.d42.knowledge.table.TableDegrade;
import com.zl.d42.knowledge.table.dto.TableExtractSummary;
import com.zl.d42.knowledge.table.dto.TableQueryHit;
import com.zl.d42.knowledge.table.dto.TableRowView;
import com.zl.d42.knowledge.table.dto.TableView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 表格结构化记录端点（P1 新建；P3 权限审计同口径：
 * 读面 AccessGuard.requireDocumentVisible 文档级校验（拒绝 AUTH_FORBIDDEN，不泄露存在性口径），
 * 重抽取写面 requireAdmin + d42_admin_audit action=TABLE_EXTRACT；
 * access disabled 时 guard Bean 缺席=匿名可用（D40 先例）；table disabled 时本控制器缺席（404 零差异）。
 * 鉴权分级（H7）：tables/query/rows=READ(visible)；extract=ADMIN+AUDIT）。
 */
@RestController
@ConditionalOnProperty(prefix = "app.knowledge.table", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TableController {

    static final String ACTION_TABLE_EXTRACT = "TABLE_EXTRACT";

    private final TableRecordService tableRecordService;
    private final KnowledgeDocumentService documentService;
    private final ObjectProvider<AccessGuard> accessGuardProvider;
    private final ObjectProvider<AuditService> auditServiceProvider;

    public TableController(TableRecordService tableRecordService,
                           KnowledgeDocumentService documentService,
                           ObjectProvider<AccessGuard> accessGuardProvider,
                           ObjectProvider<AuditService> auditServiceProvider) {
        this.tableRecordService = tableRecordService;
        this.documentService = documentService;
        this.accessGuardProvider = accessGuardProvider;
        this.auditServiceProvider = auditServiceProvider;
    }

    @GetMapping("/api/v1/knowledge/documents/{documentId}/tables")
    public Result<List<TableView>> tables(@PathVariable String documentId) {
        requireVisible(documentId);
        return Result.success(tableRecordService.listTables(documentId, null, false));
    }

    @GetMapping("/api/v1/knowledge/tables/{tableId}/rows")
    public Result<List<TableRowView>> rows(@PathVariable long tableId) {
        Map<String, Object> table = tableRecordService.tableRow(tableId);
        if (table == null) {
            throw new KnowledgeDocumentException(ResultCode.DOCUMENT_NOT_FOUND,
                    "表格记录不存在: " + tableId);
        }
        requireVisible(String.valueOf(table.get("document_id")));
        return Result.success(tableRecordService.listRows(tableId));
    }

    @GetMapping("/api/v1/knowledge/documents/{documentId}/tables/query")
    public Result<List<TableQueryHit>> query(@PathVariable String documentId,
                                             @RequestParam(required = false) String column,
                                             @RequestParam(required = false) String value) {
        if (value == null || value.isBlank()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "精确检索必须提供 value 参数", "TABLE_QUERY_VALUE_REQUIRED");
        }
        requireVisible(documentId);
        return Result.success(tableRecordService.queryByColumnValue(
                documentId, column == null ? "" : column, value));
    }

    @PostMapping("/api/v1/knowledge/documents/{documentId}/tables/extract")
    public Result<TableExtractSummary> extract(@PathVariable String documentId) {
        AuthContext actor = requireAdmin();
        KnowledgeDocument document = documentService.documentForScope(documentId);
        TableExtractSummary summary = tableRecordService.reExtract(
                documentId, document.getVersion(), document.getFileName());
        // R03 语义校准（R02 遗留瑕疵）：PARSE_FAILED 属执行失败，审计必须记 FAILED 且 reason 如实；
        // REEXTRACT_TEXT_TRACK_ONLY 维持 R01 口径（非表格域退化→FAILED 该码）
        boolean parseFailed = summary.degradedCodes().contains(TableDegrade.PARSE_FAILED);
        boolean textTrackOnly = summary.degradedCodes().contains(TableDegrade.REEXTRACT_TEXT_TRACK_ONLY);
        auditIfPresent(actor, ACTION_TABLE_EXTRACT, documentId, document.getVersion(),
                !textTrackOnly && !parseFailed,
                parseFailed ? TableDegrade.PARSE_FAILED : TableDegrade.REEXTRACT_TEXT_TRACK_ONLY);
        return Result.success(summary);
    }

    /** 读面：documentForScope 取三键（文档不存在沿用既有 4041），enabled 时同口径校验。 */
    private void requireVisible(String documentId) {
        KnowledgeDocument document = documentService.documentForScope(documentId);
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.requireDocumentVisible(document.getTenantId(),
                    document.getDeptId(), document.getDeviceId());
        }
    }

    private AuthContext requireAdmin() {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        return guard == null ? null : guard.requireAdmin();
    }

    private void auditIfPresent(AuthContext actor, String action, String target,
                                String version, boolean ok, String failedReason) {
        AuditService audit = auditServiceProvider.getIfAvailable();
        if (audit == null || actor == null) {
            return;
        }
        if (ok) {
            audit.recordAllowed(actor, action, target, version);
        } else {
            audit.recordFailed(actor, action, target, version, failedReason);
        }
    }
}
