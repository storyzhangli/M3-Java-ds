package com.zl.d42.knowledge.vectorindex.service;

import com.zl.d42.knowledge.document.KnowledgeDocument;
import com.zl.d42.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d42.knowledge.sync.DocumentSyncRepository;
import com.zl.d42.knowledge.sync.DocumentSyncRow;
import com.zl.d42.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d42.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d42.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d42.knowledge.vectorindex.model.VectorIndexStatus;
import com.zl.d42.knowledge.vectorstore.PgVectorAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D40 R01 P5：一致性核查扩展检测组件测试（E4 卡死 / E5 版本指针漂移，H4 只检测不直改）。
 *
 * <p>桩仅替代两个数据库的读取边界，漂移判定逻辑全部走真实
 * {@link VectorReconciliationService#reconcile(boolean)}。</p>
 */
class VectorReconciliationServiceDriftTest {

    private PgVectorAccess pgVectorAccess;

    private VectorChunkRepository chunkRepository;

    private DocumentSyncRepository syncRepository;

    private KnowledgeDocumentRepository documentRepository;

    private VectorReconciliationService service;

    @BeforeEach
    void setUp() {
        pgVectorAccess = mock(PgVectorAccess.class);
        chunkRepository = mock(VectorChunkRepository.class);
        syncRepository = mock(DocumentSyncRepository.class);
        documentRepository = mock(KnowledgeDocumentRepository.class);
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setStuckMinutes(30);
        service = new VectorReconciliationService(
                mock(VectorStore.class), pgVectorAccess, chunkRepository,
                mock(DocumentVectorIndexService.class), properties,
                syncRepository, documentRepository);

        // PG 端：chunk c1 版本 v2（外部直写脱节）
        when(pgVectorAccess.listAll()).thenReturn(List.of(
                new PgVectorAccess.PgVectorRow("c1", "d42t-doc-1", "v2")));
        when(pgVectorAccess.countByDocumentVersion(anyString(), anyString())).thenReturn(1L);

        // MySQL 关联端：chunk c1 版本 v1
        VectorChunkRow chunk = new VectorChunkRow();
        chunk.setChunkId("c1");
        chunk.setDocumentId("d42t-doc-1");
        chunk.setVersion("v1");
        when(chunkRepository.findAllChunks()).thenReturn(List.of(chunk));

        // 任务端：同文档双 COMPLETED（删除闭环应收敛其一为 SUPERSEDED）
        when(chunkRepository.findAllTasks()).thenReturn(List.of(
                task("d42t-doc-1", "v1"), task("d42t-doc-1", "v2")));

        // 权威指针：knowledge_document.version=v9 与 active 均脱节
        KnowledgeDocument doc = mock(KnowledgeDocument.class);
        when(doc.getVersion()).thenReturn("v9");
        when(documentRepository.findByDocumentId("d42t-doc-1"))
                .thenReturn(Optional.of(doc));

        // 同步状态机：一条超龄 FAILED 卡死行
        DocumentSyncRow stuck = new DocumentSyncRow();
        stuck.setDocumentId("d42t-doc-1");
        stuck.setOperation("DELETE");
        stuck.setVersion("v2");
        stuck.setSyncStatus("FAILED");
        stuck.setReasonCode("CONFIRM_MISMATCH");
        stuck.setAttemptCount(1);
        stuck.setUpdatedAt(LocalDateTime.now().minusHours(1));
        when(syncRepository.findStuck(anyInt())).thenReturn(List.of(stuck));
    }

    private static VectorIndexTaskRow task(String documentId, String version) {
        VectorIndexTaskRow row = new VectorIndexTaskRow();
        row.setDocumentId(documentId);
        row.setVersion(version);
        row.setStatus(VectorIndexStatus.COMPLETED.name());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    @Test
    @DisplayName("E5 T-RD-01 指针漂移：POINTER/MULTI-COMPLETED/PG-vs-关联三类均检出")
    void detectsAllVersionDriftFlavours() {
        ReconciliationReportView report = service.reconcile(true);
        List<String> keys = report.getVersionMismatches().stream()
                .map(ReconciliationReportView.Issue::getKey).toList();
        assertTrue(keys.contains("POINTER:d42t-doc-1"), () -> keys.toString());
        assertTrue(keys.contains("MULTI-COMPLETED:d42t-doc-1"), () -> keys.toString());
        assertTrue(keys.contains("c1"), () -> keys.toString());
        assertEquals(3, report.getVersionMismatches().size());
    }

    @Test
    @DisplayName("E4 T-RD-02 SYNC_STUCK：超龄非 SYNCED 同步行进入独立清单")
    void detectsSyncStuck() {
        ReconciliationReportView report = service.reconcile(true);
        assertEquals(1, report.getSyncStuck().size());
        ReconciliationReportView.Issue issue = report.getSyncStuck().get(0);
        assertEquals("DELETE:d42t-doc-1:v2", issue.getKey());
        assertTrue(issue.getDetail().contains("CONFIRM_MISMATCH"));
    }

    @Test
    @DisplayName("H4 T-RD-03 dryRun=true 不执行任何补偿动作（只报告）")
    void dryRunPerformsNoCompensation() {
        ReconciliationReportView report = service.reconcile(true);
        assertTrue(report.getActionsTaken().isEmpty());
        assertTrue(report.totalIssues() > 0);
    }
}
