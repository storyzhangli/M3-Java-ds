package com.zl.d40.knowledge.vectorindex.service;

import com.zl.d40.common.ResultCode;
import com.zl.d40.knowledge.chunking.ChunkBoundaryType;
import com.zl.d40.knowledge.chunking.ChunkStrategy;
import com.zl.d40.knowledge.chunking.DocumentChunk;
import com.zl.d40.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d40.knowledge.document.KnowledgeDocumentException;
import com.zl.d40.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d40.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d40.knowledge.sync.DocumentSyncService;
import com.zl.d40.knowledge.sync.InMemoryDocumentSyncRepository;
import com.zl.d40.knowledge.sync.SyncOperation;
import com.zl.d40.knowledge.vectorindex.VectorIndexException;
import com.zl.d40.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d40.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d40.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d40.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d40.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d40.knowledge.vectorindex.support.ContentHasher;
import com.zl.d40.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d40.knowledge.vectorstore.PgVectorAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D40 R03-07 索引失败分类与恢复测试（T-R03-22~30）。
 *
 * <p>覆盖任务书§九的八类场景，并显式区分六类失败：解析失败（KnowledgeDocumentException）、
 * Chunk 生成失败（IllegalArgumentException：空白/顺序）、Embedding/PG 写入失败
 * （EMBED_WRITE_FAILED，Spring AI VectorStore.add 将 Embedding 与写入聚合为单一调用，
 * 框架边界内不可再分，原始异常文本保留于 errorMessage 供追踪）、MySQL 关联写入失败
 * （快照阶段原异常上抛，任务停留 INDEXING/REGISTER）、双端核验失败（COUNT_MISMATCH）。</p>
 *
 * <p>仓储与向量库用有状态内存替身（语义对齐生产 Upsert/唯一键），业务服务为真实实现；
 * 不伪称真实数据库集成（真实交互见 E1–E5 演练证据）。</p>
 */
class IndexFailureRecoveryTest {

    private static final String DOC = "d40t-index-doc";
    private static final String VER = "1";

    private VectorStore vectorStore;
    private PgVectorAccess pgVectorAccess;
    private VectorChunkRepository repository;
    private KnowledgeDocumentChunkingService chunking;
    private InMemoryDocumentSyncRepository syncRepository;
    private DocumentVectorIndexService indexService;

    /** 任务行内存表：自然键 doc|version。 */
    private final Map<String, VectorIndexTaskRow> tasks = new HashMap<>();
    /** 关联行内存表：自然键 doc|version → 按 chunkIndex 排序的行。 */
    private final Map<String, List<VectorChunkRow>> chunks = new HashMap<>();
    /** PG 向量内存表：doc|version → 已写入 Document。 */
    private final Map<String, List<Document>> pgState = new HashMap<>();
    /** add 调用计数（故障注入用）。 */
    private final AtomicInteger addCalls = new AtomicInteger();
    /** 第 N 次 add 抛异常；0=永不。 */
    private volatile int failAddCall = 0;
    private volatile String failAddMessage = "simulated PG write failure";

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        pgVectorAccess = mock(PgVectorAccess.class);
        repository = mock(VectorChunkRepository.class);
        chunking = mock(KnowledgeDocumentChunkingService.class);
        KnowledgeDocumentRepository docMeta = mock(KnowledgeDocumentRepository.class);
        EmbeddingModelDescriptor descriptor = mock(EmbeddingModelDescriptor.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(descriptor.vectorSpaceId()).thenReturn("LOCAL_HASH_384");
        when(descriptor.modelName()).thenReturn("local-hashing-test");
        when(docMeta.findByDocumentId(anyString())).thenReturn(Optional.empty());

        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setBatchSize(1);
        properties.setMaxAttempts(3);
        properties.setRetryBackoffMs(50);
        properties.setStuckMinutes(30);

        syncRepository = new InMemoryDocumentSyncRepository();
        DocumentSyncService syncService = new DocumentSyncService(syncRepository, properties);
        indexService = new DocumentVectorIndexService(vectorStore, pgVectorAccess,
                repository, new StableChunkIdGenerator(), new ContentHasher(),
                properties, descriptor, chunking, txManager, docMeta);
        indexService.setSyncService(syncService);
        indexService.registerSyncHandlers();

        stubRepository();
        stubVectorStore();
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            return (long) pgState.getOrDefault(key, List.of()).size();
        }).when(pgVectorAccess).countByDocumentVersion(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 有状态替身（语义对齐生产 SQL）
    // ------------------------------------------------------------------

    private void stubRepository() {
        doAnswer(inv -> Optional.ofNullable(tasks.get(inv.getArgument(0) + "|" + inv.getArgument(1))))
                .when(repository).findTask(anyString(), anyString());
        doAnswer(inv -> {
            VectorIndexTaskRow row = inv.getArgument(0);
            tasks.put(row.getDocumentId() + "|" + row.getVersion(), row);
            return null;
        }).when(repository).upsertTask(any());
        doAnswer(inv -> {
            VectorIndexTaskRow row = inv.getArgument(0);
            tasks.put(row.getDocumentId() + "|" + row.getVersion(), row);
            return 1;
        }).when(repository).updateTaskState(any());
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            VectorIndexTaskRow row = tasks.get(key);
            if (row == null) {
                return 0;
            }
            row.setStatus(inv.getArgument(2));
            row.setStage(inv.getArgument(3));
            row.setErrorCode(inv.getArgument(4));
            row.setErrorMessage(inv.getArgument(5));
            row.setAttemptCount(inv.getArgument(6));
            row.setNextRetryAt(inv.getArgument(7));
            row.setUpdatedAt(inv.getArgument(8));
            return 1;
        }).when(repository).updateTaskProgress(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), any(), any());
        doAnswer(inv -> {
            String doc = inv.getArgument(0);
            List<VectorIndexTaskRow> rows = new ArrayList<>();
            for (Map.Entry<String, VectorIndexTaskRow> e : tasks.entrySet()) {
                if (e.getKey().startsWith(doc + "|")) {
                    rows.add(e.getValue());
                }
            }
            rows.sort(Comparator.comparing(VectorIndexTaskRow::getVersion));
            return rows;
        }).when(repository).findTasksByDocument(anyString());
        doAnswer(inv -> new ArrayList<>(chunks.getOrDefault(
                inv.getArgument(0) + "|" + inv.getArgument(1), List.of())))
                .when(repository).findChunks(anyString(), anyString());
        doAnswer(inv -> {
            List<VectorChunkRow> rows = inv.getArgument(0);
            for (VectorChunkRow row : rows) {
                String key = row.getDocumentId() + "|" + row.getVersion();
                List<VectorChunkRow> list = chunks.computeIfAbsent(key, k -> new ArrayList<>());
                list.removeIf(r -> r.getChunkId().equals(row.getChunkId()));
                list.add(row);
                list.sort(Comparator.comparingInt(VectorChunkRow::getChunkIndex));
            }
            return null;
        }).when(repository).upsertChunks(anyList());
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            List<String> ids = inv.getArgument(2);
            String status = inv.getArgument(3);
            int n = 0;
            for (VectorChunkRow row : chunks.getOrDefault(key, List.of())) {
                if (ids.contains(row.getChunkId())) {
                    row.setStatus(status);
                    n++;
                }
            }
            return n;
        }).when(repository).updateChunkStatuses(anyString(), anyString(), anyList(), anyString(), any());
        doAnswer(inv -> chunks.getOrDefault(inv.getArgument(0) + "|" + inv.getArgument(1), List.of()).size())
                .when(repository).countChunks(anyString(), anyString());
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            String status = inv.getArgument(2);
            return (int) chunks.getOrDefault(key, List.of()).stream()
                    .filter(r -> status.equals(r.getStatus())).count();
        }).when(repository).countChunksByStatus(anyString(), anyString(), anyString());
        doAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            int fromIndex = inv.getArgument(2);
            List<VectorChunkRow> list = chunks.getOrDefault(key, List.of());
            int before = list.size();
            list.removeIf(r -> r.getChunkIndex() >= fromIndex);
            return before - list.size();
        }).when(repository).deleteChunksFromIndex(anyString(), anyString(), anyInt());
        doAnswer(inv -> {
            List<VectorChunkRow> removed = chunks.remove(inv.getArgument(0) + "|" + inv.getArgument(1));
            return removed == null ? 0 : removed.size();
        }).when(repository).deleteChunks(anyString(), anyString());
        doAnswer(inv -> tasks.remove(inv.getArgument(0) + "|" + inv.getArgument(1)) == null ? 0 : 1)
                .when(repository).deleteTask(anyString(), anyString());
    }

    private void stubVectorStore() {
        doAnswer(inv -> {
            List<Document> docs = inv.getArgument(0);
            int call = addCalls.incrementAndGet();
            if (failAddCall > 0 && call == failAddCall) {
                throw new RuntimeException(failAddMessage);
            }
            for (Document d : docs) {
                String key = d.getMetadata().get("documentId") + "|" + d.getMetadata().get("version");
                List<Document> list = pgState.computeIfAbsent(key, k -> new ArrayList<>());
                list.removeIf(x -> x.getId().equals(d.getId()));
                list.add(d);
            }
            return null;
        }).when(vectorStore).add(anyList());
        doAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            for (List<Document> list : pgState.values()) {
                list.removeIf(d -> ids.contains(d.getId()));
            }
            return null;
        }).when(vectorStore).delete(anyList());
    }

    private List<DocumentChunk> threeChunks() {
        List<DocumentChunk> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            list.add(chunk(i, "content-" + i));
        }
        return list;
    }

    private DocumentChunk chunk(int index, String content) {
        return new DocumentChunk(DOC, "title", VER, index, ChunkStrategy.FIXED_LENGTH,
                content, content.length(), List.of(1), List.of(), ChunkBoundaryType.HARD_LIMIT);
    }

    private VectorIndexTaskRow task() {
        return tasks.get(DOC + "|" + VER);
    }

    private void assertNoSyncNoise() {
        assertTrue(syncService_findDelete().isEmpty(), "索引路径不得产生 DELETE 同步噪音行");
    }

    private Optional<com.zl.d40.knowledge.sync.DocumentSyncRow> syncService_findDelete() {
        return new DocumentSyncService(syncRepository, new VectorIndexProperties())
                .find(DOC, SyncOperation.DELETE, VER);
    }

    // ------------------------------------------------------------------
    // T-R03-22~30
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T-R03-22 解析失败：异常上抛且不产生任务行/向量/同步噪音")
    void parseFailureLeavesNoState() {
        doThrow(new KnowledgeDocumentException(ResultCode.DOCUMENT_PARSE_FAILED, "文件基础解析失败"))
                .when(chunking).preview(anyString(), any());
        assertThrows(KnowledgeDocumentException.class,
                () -> indexService.indexFromPages(DOC, null));
        assertTrue(tasks.isEmpty());
        assertTrue(pgState.isEmpty());
        assertNoSyncNoise();
    }

    @Test
    @DisplayName("T-R03-23 空白 Chunk：拒绝写入且不产生任何状态")
    void blankChunkRejected() {
        List<DocumentChunk> input = List.of(chunk(0, "ok"), chunk(1, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, input));
        assertTrue(tasks.isEmpty());
        assertTrue(pgState.isEmpty());
        assertEquals(0, addCalls.get());
        assertNoSyncNoise();
    }

    @Test
    @DisplayName("T-R03-24 Chunk 顺序不连续：拒绝写入")
    void nonContiguousChunkIndexRejected() {
        List<DocumentChunk> input = List.of(chunk(0, "a"), chunk(2, "c"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, input));
        assertTrue(ex.getMessage().contains("0..n-1"));
        assertTrue(tasks.isEmpty());
        assertEquals(0, addCalls.get());
    }

    @Test
    @DisplayName("T-R03-25 部分写入失败：PARTIAL/EMBED_WRITE/EMBED_WRITE_FAILED，processed 可追踪")
    void partialWriteFailureClassified() {
        failAddCall = 2;
        VectorIndexException ex = assertThrows(VectorIndexException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, threeChunks()));
        assertEquals("EMBED_WRITE_FAILED", ex.getErrorCode());
        VectorIndexTaskRow row = task();
        assertEquals("PARTIAL", row.getStatus());
        assertEquals("EMBED_WRITE", row.getStage());
        assertEquals("EMBED_WRITE_FAILED", row.getErrorCode());
        assertEquals(1, row.getAttemptCount());
        assertEquals(1, pgState.get(DOC + "|" + VER).size(), "仅第一批写入成功");
        assertEquals(1, repository.countChunksByStatus(DOC, VER, "INDEXED"));
        assertEquals(2, repository.countChunksByStatus(DOC, VER, "PENDING"));
        assertNoSyncNoise();
    }

    @Test
    @DisplayName("T-R03-26 双端核验失败：COUNT_MISMATCH 与写入失败分类不同")
    void countMismatchClassifiedSeparately() {
        doReturn(2L).when(pgVectorAccess).countByDocumentVersion(DOC, VER);
        VectorIndexException ex = assertThrows(VectorIndexException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, threeChunks()));
        assertEquals("COUNT_MISMATCH", ex.getErrorCode());
        VectorIndexTaskRow row = task();
        assertEquals("PARTIAL", row.getStatus());
        assertEquals("VERIFY", row.getStage());
        assertEquals("COUNT_MISMATCH", row.getErrorCode());
    }

    @Test
    @DisplayName("T-R03-27 部分写入后重试：恢复 COMPLETED 且双端数量一致")
    void retryAfterPartialConverges() {
        failAddCall = 2;
        assertThrows(VectorIndexException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, threeChunks()));
        failAddCall = 0;
        VectorIndexResultView result = indexService.indexChunks(DOC, VER, null, null, threeChunks());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(2, result.getAttemptCount());
        assertFalse(result.isIdempotent());
        assertEquals(3, pgState.get(DOC + "|" + VER).size());
        assertEquals(3, repository.countChunks(DOC, VER));
        assertEquals(3, repository.countChunksByStatus(DOC, VER, "INDEXED"));
        assertEquals("COMPLETED", task().getStatus());
    }

    @Test
    @DisplayName("T-R03-28 恢复后重复执行：幂等短路，无幽灵向量/重复关联")
    void repeatAfterRecoveryIsIdempotent() {
        indexService.indexChunks(DOC, VER, null, null, threeChunks());
        int addsBefore = addCalls.get();
        VectorIndexResultView again = indexService.indexChunks(DOC, VER, null, null, threeChunks());
        assertTrue(again.isIdempotent());
        assertEquals(0, again.getProcessed());
        assertEquals(addsBefore, addCalls.get(), "幂等短路不得再写 PG");
        assertEquals(3, pgState.get(DOC + "|" + VER).size());
        assertEquals(3, repository.countChunks(DOC, VER));
        assertEquals(1, tasks.size());
    }

    @Test
    @DisplayName("T-R03-29 MySQL 关联写入失败：原异常上抛，任务停留 INDEXING/REGISTER（区别于 PG 失败）")
    void mysqlSnapshotFailureDistinctFromPgFailure() {
        doThrow(new RuntimeException("simulated MySQL snapshot failure"))
                .when(repository).upsertChunks(anyList());
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, threeChunks()));
        assertTrue(ex.getMessage().contains("MySQL snapshot"));
        VectorIndexTaskRow row = task();
        assertEquals("INDEXING", row.getStatus());
        assertEquals("REGISTER", row.getStage());
        assertEquals("", row.getErrorCode());
        assertEquals(0, addCalls.get(), "快照失败不得触发 PG 写入");
        assertTrue(pgState.isEmpty());
    }

    @Test
    @DisplayName("T-R03-30 Embedding 失败可追踪：框架边界归类 EMBED_WRITE_FAILED 且保留原始文本")
    void embeddingFailureTraceable() {
        failAddCall = 1;
        failAddMessage = "embedding model timeout after 30s";
        VectorIndexException ex = assertThrows(VectorIndexException.class,
                () -> indexService.indexChunks(DOC, VER, null, null, threeChunks()));
        assertEquals("EMBED_WRITE_FAILED", ex.getErrorCode());
        assertTrue(task().getErrorMessage().contains("embedding model timeout"));
        assertEquals("FAILED", task().getStatus(), "processed=0 应为 FAILED 而非 PARTIAL");
    }
}
