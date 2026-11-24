package com.zl.d41.knowledge.vectorindex.service;

import com.zl.d41.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d41.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d41.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d41.knowledge.sync.DocumentSyncRow;
import com.zl.d41.knowledge.sync.DocumentSyncService;
import com.zl.d41.knowledge.sync.InMemoryDocumentSyncRepository;
import com.zl.d41.knowledge.sync.SyncOperation;
import com.zl.d41.knowledge.sync.SyncReasonCode;
import com.zl.d41.knowledge.sync.SyncStatus;
import com.zl.d41.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d41.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d41.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d41.knowledge.vectorindex.support.ContentHasher;
import com.zl.d41.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d41.knowledge.vectorstore.PgVectorAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D40 R03-04 真实删除调用链组合测试（T-R03-13~21）。
 *
 * <p>覆盖真实业务链：retryOne → DELETE Handler → deleteClosedLoop → PG 向量删除
 * → MySQL 关联清理 → 双端确认 → markSynced / markFailed。状态机与业务闭环均为
 * 真实实现；Mockito 仅替代存储层（VectorStore/PgVectorAccess/VectorChunkRepository），
 * 同步仓储用与生产 SQL 语义对齐的内存 CAS 替身。本类是组件级组合测试，
 * 不伪称真实数据库集成测试（真实 MySQL/PG 交互见 E1–E5 演练证据）。</p>
 */
class DeleteClosedLoopChainTest {

    private static final String DOC = "d41t-chain-doc";
    private static final String VER = "1";

    private VectorStore vectorStore;
    private PgVectorAccess pgVectorAccess;
    private VectorChunkRepository chunkRepository;
    private InMemoryDocumentSyncRepository syncRepository;
    private DocumentSyncService syncService;
    private DocumentVectorIndexService indexService;

    /** 模拟 MySQL 关联行状态（findChunks/deleteChunks 有状态读写）。 */
    private final Map<String, List<VectorChunkRow>> chunkState = new HashMap<>();

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        pgVectorAccess = mock(PgVectorAccess.class);
        chunkRepository = mock(VectorChunkRepository.class);
        KnowledgeDocumentRepository docMeta = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocumentChunkingService chunking = mock(KnowledgeDocumentChunkingService.class);
        EmbeddingModelDescriptor descriptor = mock(EmbeddingModelDescriptor.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(descriptor.vectorSpaceId()).thenReturn("LOCAL_HASH_384");

        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoffMs(50);
        properties.setStuckMinutes(30);

        syncRepository = new InMemoryDocumentSyncRepository();
        syncService = new DocumentSyncService(syncRepository, properties);
        indexService = new DocumentVectorIndexService(vectorStore, pgVectorAccess,
                chunkRepository, new StableChunkIdGenerator(), new ContentHasher(),
                properties, descriptor, chunking, txManager, docMeta);
        indexService.setSyncService(syncService);
        indexService.registerSyncHandlers();

        // 默认现场：2 个关联行，PG 残留 0
        seedChunks(DOC, VER, "chunk-a", "chunk-b");
        when(pgVectorAccess.countByDocumentVersion(anyString(), anyString())).thenReturn(0L);
    }

    private void seedChunks(String doc, String ver, String... chunkIds) {
        List<VectorChunkRow> rows = new ArrayList<>();
        for (String id : chunkIds) {
            VectorChunkRow row = new VectorChunkRow();
            row.setDocumentId(doc);
            row.setVersion(ver);
            row.setChunkId(id);
            row.setStatus("INDEXED");
            rows.add(row);
        }
        chunkState.put(doc + "|" + ver, rows);
        // doAnswer 风格重桩：避免 when() 触发既有 answer 的副作用
        org.mockito.Mockito.doAnswer(inv -> new ArrayList<>(chunkState.getOrDefault(doc + "|" + ver,
                List.of()))).when(chunkRepository).findChunks(doc, ver);
        org.mockito.Mockito.doAnswer(inv -> {
            List<VectorChunkRow> removed = chunkState.remove(doc + "|" + ver);
            return removed == null ? 0 : removed.size();
        }).when(chunkRepository).deleteChunks(doc, ver);
        org.mockito.Mockito.doReturn(1).when(chunkRepository).deleteTask(doc, ver);
    }

    private DocumentSyncRow syncRow(SyncOperation operation) {
        return syncService.find(DOC, operation, VER).orElseThrow();
    }

    @Test
    @DisplayName("T-R03-13 正常删除：真实闭环全链 → SYNCED")
    void normalDeleteConvergesSynced() {
        indexService.deleteByDocumentVersion(DOC, VER);
        DocumentSyncRow row = syncRow(SyncOperation.DELETE);
        assertEquals(SyncStatus.SYNCED.name(), row.getSyncStatus());
        assertEquals(0, row.getAttemptCount());
        verify(vectorStore).delete(anyList());
        verify(chunkRepository).deleteChunks(DOC, VER);
        assertTrue(chunkState.isEmpty(), "MySQL 关联必须被真实清理");
    }

    @Test
    @DisplayName("T-R03-14 PG 删除失败：FAILED + PG_UNREACHABLE + attempt=1")
    void pgDeleteFailureRecorded() {
        doThrow(new RuntimeException("Connect timed out")).when(vectorStore).delete(anyList());
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        DocumentSyncRow row = syncRow(SyncOperation.DELETE);
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.PG_UNREACHABLE, row.getReasonCode());
        assertEquals(1, row.getAttemptCount());
        assertFalse(row.getPayloadHash().isBlank(), "保留可恢复载荷指纹");
    }

    @Test
    @DisplayName("T-R03-15 MySQL 关联清理失败：FAILED + CONFIRM_MISMATCH + 可恢复信息")
    void mysqlCleanupFailureRecorded() {
        org.mockito.Mockito.doThrow(new RuntimeException("mysql connection reset"))
                .when(chunkRepository).deleteChunks(DOC, VER);
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        DocumentSyncRow row = syncRow(SyncOperation.DELETE);
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.CONFIRM_MISMATCH, row.getReasonCode());
        assertEquals(1, row.getAttemptCount());
        assertFalse(row.getPayloadHash().isBlank());
    }

    @Test
    @DisplayName("T-R03-16 PG 残留不为 0：双端确认 FAILED + CONFIRM_MISMATCH")
    void pgResidualBlocksSynced() {
        when(pgVectorAccess.countByDocumentVersion(DOC, VER)).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        DocumentSyncRow row = syncRow(SyncOperation.DELETE);
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.CONFIRM_MISMATCH, row.getReasonCode());
    }

    @Test
    @DisplayName("T-R03-17 MySQL 关联残留不为 0：双端确认 FAILED + CONFIRM_MISMATCH")
    void relationResidualBlocksSynced() {
        // deleteChunks 报告成功但状态未清（模拟清理未生效）
        org.mockito.Mockito.doReturn(2).when(chunkRepository).deleteChunks(DOC, VER);
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        DocumentSyncRow row = syncRow(SyncOperation.DELETE);
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.CONFIRM_MISMATCH, row.getReasonCode());
    }

    @Test
    @DisplayName("T-R03-18 PG 成功 MySQL 失败后重试：最终双端收敛 SYNCED")
    void retryAfterMysqlFailureConverges() {
        org.mockito.Mockito.doThrow(new RuntimeException("mysql deadlock"))
                .when(chunkRepository).deleteChunks(DOC, VER);
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        assertEquals(SyncStatus.FAILED.name(), syncRow(SyncOperation.DELETE).getSyncStatus());
        // 恢复 MySQL：重新装配有状态清理 stub
        seedChunks(DOC, VER, "chunk-a", "chunk-b");
        String outcome = syncService.retryOne(DOC, SyncOperation.DELETE, VER);
        assertEquals(SyncStatus.SYNCED.name(), outcome);
        assertTrue(chunkState.isEmpty());
        verify(vectorStore, times(2)).delete(anyList());
    }

    @Test
    @DisplayName("T-R03-19 管线已 markFailed 后抛异常：重试失败计数只 +1（1→2 而非 3）")
    void pipelineRecordedFailureNotDoubleCountedOnRetry() {
        doThrow(new RuntimeException("Connect timed out")).when(vectorStore).delete(anyList());
        assertThrows(RuntimeException.class, () -> indexService.deleteByDocumentVersion(DOC, VER));
        assertEquals(1, syncRow(SyncOperation.DELETE).getAttemptCount());
        String outcome = syncService.retryOne(DOC, SyncOperation.DELETE, VER);
        assertEquals(SyncStatus.FAILED.name(), outcome);
        assertEquals(2, syncRow(SyncOperation.DELETE).getAttemptCount(),
                "deleteClosedLoop 内层落账后外层不得重复计数");
    }

    @Test
    @DisplayName("T-R03-20 同一事件重复删除：幂等无破坏，SYNCED 行 retryOne 为 no-op")
    void repeatedDeleteIsIdempotent() {
        indexService.deleteByDocumentVersion(DOC, VER);
        assertEquals(SyncStatus.SYNCED.name(), syncRow(SyncOperation.DELETE).getSyncStatus());
        // 重复业务删除：新事件重开并再次收敛，不产生第二行
        indexService.deleteByDocumentVersion(DOC, VER);
        assertEquals(SyncStatus.SYNCED.name(), syncRow(SyncOperation.DELETE).getSyncStatus());
        assertEquals(1, syncRepository.findByDocument(DOC).size(), "自然键唯一，不产生重复行");
        assertEquals("NO_OP_ALREADY_SYNCED",
                syncService.retryOne(DOC, SyncOperation.DELETE, VER));
    }

    @Test
    @DisplayName("T-R03-21 UPDATE_CLEANUP 失败后恢复：旧版本最终清理完成")
    void updateCleanupFailureThenRecovery() {
        // 预置失败遗留行（模拟清理钩子失败落库）
        syncService.markPending(DOC, SyncOperation.UPDATE_CLEANUP, VER, "h-old");
        syncService.markFailed(DOC, SyncOperation.UPDATE_CLEANUP, VER,
                SyncReasonCode.PG_DELETE_FAILED, "h-old");
        // 第一次恢复：PG 仍故障 → cleanup 捕获并落账（单层），attempt 1→2
        doThrow(new RuntimeException("socket closed")).when(vectorStore).delete(anyList());
        String first = syncService.retryOne(DOC, SyncOperation.UPDATE_CLEANUP, VER);
        assertEquals(SyncStatus.FAILED.name(), first);
        assertEquals(2, syncRow(SyncOperation.UPDATE_CLEANUP).getAttemptCount());
        // 第二次恢复：PG 恢复 → 清理完成 → markSyncedIfRowExists 收敛
        org.mockito.Mockito.reset(vectorStore);
        String second = syncService.retryOne(DOC, SyncOperation.UPDATE_CLEANUP, VER);
        assertEquals(SyncStatus.SYNCED.name(), second);
        assertTrue(chunkState.isEmpty(), "旧版本关联必须被真实清理");
        verify(chunkRepository).deleteChunks(DOC, VER);
    }
}
