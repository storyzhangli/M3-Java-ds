package com.zl.d41.knowledge.table;

import com.zl.d41.access.AccessGuard;
import com.zl.d41.access.AuthContext;
import com.zl.d41.knowledge.document.KnowledgeDocument;
import com.zl.d41.knowledge.document.KnowledgeDocumentService;
import com.zl.d41.knowledge.sync.AuditService;
import com.zl.d41.knowledge.table.dto.TableExtractSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R03 审计语义校准：TABLE_EXTRACT 审计对 PARSE_FAILED 必须记 FAILED（reason=PARSE_FAILED），
 * 修复 R02 遗留瑕疵（此前 PARSE_FAILED 被记 ALLOWED）。
 */
class TableControllerAuditTest {

    private static final AuthContext ADMIN =
            new AuthContext("u1", "T1", "D-HQ", Set.of(), "ADMIN");

    private TableRecordService tableService;
    private AuditService audit;
    private TableController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        tableService = mock(TableRecordService.class);
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocument document = mock(KnowledgeDocument.class);
        when(document.getVersion()).thenReturn("1");
        when(document.getFileName()).thenReturn("m.pdf");
        when(documentService.documentForScope("doc-1")).thenReturn(document);

        AccessGuard guard = mock(AccessGuard.class);
        when(guard.requireAdmin()).thenReturn(ADMIN);
        ObjectProvider<AccessGuard> guardProvider = mock(ObjectProvider.class);
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        audit = mock(AuditService.class);
        ObjectProvider<AuditService> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);

        controller = new TableController(tableService, documentService, guardProvider, auditProvider);
    }

    private TableExtractSummary summaryWith(List<String> codes) {
        return new TableExtractSummary("doc-1", "1", 0, 0, 0, 0, codes);
    }

    /** PARSE_FAILED：重抽取执行失败 → 审计 FAILED reason=PARSE_FAILED，绝不记 ALLOWED。 */
    @Test
    void parseFailedAuditsAsFailedWithHonestReason() {
        when(tableService.reExtract("doc-1", "1", "m.pdf"))
                .thenReturn(summaryWith(List.of(TableDegrade.PARSE_FAILED)));
        controller.extract("doc-1");
        verify(audit).recordFailed(ADMIN, TableController.ACTION_TABLE_EXTRACT,
                "doc-1", "1", TableDegrade.PARSE_FAILED);
        verify(audit, never()).recordAllowed(any(), anyString(), anyString(), anyString());
    }

    /** REEXTRACT_TEXT_TRACK_ONLY：维持 R01 口径 FAILED + 原 reason（回归锁）。 */
    @Test
    void textTrackOnlyKeepsR01AuditSemantics() {
        when(tableService.reExtract("doc-1", "1", "m.pdf"))
                .thenReturn(summaryWith(List.of(TableDegrade.REEXTRACT_TEXT_TRACK_ONLY)));
        controller.extract("doc-1");
        verify(audit).recordFailed(ADMIN, TableController.ACTION_TABLE_EXTRACT,
                "doc-1", "1", TableDegrade.REEXTRACT_TEXT_TRACK_ONLY);
    }

    /** 正常执行（含非失败退化如 PARSE 成功+空）→ ALLOWED。 */
    @Test
    void normalExtractionAuditsAllowed() {
        when(tableService.reExtract("doc-1", "1", "m.pdf"))
                .thenReturn(summaryWith(List.of()));
        controller.extract("doc-1");
        verify(audit).recordAllowed(ADMIN, TableController.ACTION_TABLE_EXTRACT, "doc-1", "1");
        verify(audit, never()).recordFailed(any(), anyString(), anyString(), anyString(), anyString());
    }

    /** 双码并存（文本轨退化+解析失败）：PARSE_FAILED 优先，如实记失败。 */
    @Test
    void parseFailedTakesPrecedenceWhenBothPresent() {
        when(tableService.reExtract("doc-1", "1", "m.pdf"))
                .thenReturn(summaryWith(List.of(TableDegrade.REEXTRACT_TEXT_TRACK_ONLY,
                        TableDegrade.PARSE_FAILED)));
        controller.extract("doc-1");
        verify(audit).recordFailed(ADMIN, TableController.ACTION_TABLE_EXTRACT,
                "doc-1", "1", TableDegrade.PARSE_FAILED);
    }
}
