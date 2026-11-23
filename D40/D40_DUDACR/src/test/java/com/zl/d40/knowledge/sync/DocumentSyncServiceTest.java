package com.zl.d40.knowledge.sync;

import com.zl.d40.knowledge.vectorindex.config.VectorIndexProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D40 R01 P5：操作级同步状态机组件测试（E1/E3/E4 状态机面）。
 *
 * <p>零 DB：经内存假仓储（覆写 DocumentSyncRepository 全部公共方法，父类
 * JdbcTemplate 传 null 不触达），逐条验证 KP4 冻结迁移语义。Mock 仅替代存储层，
 * 状态机判断逻辑为真实实现（不伪称端到端）。</p>
 */
class DocumentSyncServiceTest {

    private InMemorySyncRepository repository;

    private VectorIndexProperties properties;

    private DocumentSyncService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySyncRepository();
        properties = new VectorIndexProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoffMs(50);
        properties.setStuckMinutes(30);
        service = new DocumentSyncService(repository, properties);
    }

    private static String key(String docId, SyncOperation op, String version) {
        return docId + "|" + op.name() + "|" + (version == null ? "" : version);
    }

    @Test
    @DisplayName("T-SM-01 markPending 新建 PENDING 行，attempt=0")
    void markPendingCreatesRow() {
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.PENDING.name(), row.getSyncStatus());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-SM-02 markFailed 首次失败：FAILED+原因码+attempt1+退避时间非空")
    void firstFailureSetsBackoff() {
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.PG_DELETE_FAILED, row.getReasonCode());
        assertEquals(1, row.getAttemptCount());
        assertNotNull(row.getNextRetryAt());
    }

    @Test
    @DisplayName("T-SM-03 达 maxAttempts：原因码收敛 RETRY_EXHAUSTED 且 nextRetryAt 置空")
    void exhaustedStopsAutoRetry() {
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        for (int i = 0; i < 3; i++) {
            service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                    SyncReasonCode.PG_DELETE_FAILED, "h1");
        }
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.FAILED.name(), row.getSyncStatus());
        assertEquals(SyncReasonCode.RETRY_EXHAUSTED, row.getReasonCode());
        assertEquals(3, row.getAttemptCount());
        assertNull(row.getNextRetryAt());
    }

    @Test
    @DisplayName("T-SM-04 markSynced：状态 SYNCED、原因码清空、attempt 归零")
    void markSyncedClearsReason() {
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_UNREACHABLE, "h1");
        service.markSynced("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.SYNCED.name(), row.getSyncStatus());
        assertEquals("", row.getReasonCode());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-SM-05 markSynced 行不存在时防御性插入（幂等收敛）")
    void markSyncedInsertsWhenMissing() {
        service.markSynced("d40t-doc-2", SyncOperation.UPDATE_CLEANUP, "v1", "h2");
        DocumentSyncRow row =
                service.find("d40t-doc-2", SyncOperation.UPDATE_CLEANUP, "v1").orElseThrow();
        assertEquals(SyncStatus.SYNCED.name(), row.getSyncStatus());
    }

    @Test
    @DisplayName("T-SM-06 markSyncedIfRowExists 不产噪音行（成功路径零写入）")
    void markSyncedIfRowExistsNoNoise() {
        service.markSyncedIfRowExists("d40t-doc-3", SyncOperation.DELETE, "v1", "h3");
        assertTrue(service.find("d40t-doc-3", SyncOperation.DELETE, "v1").isEmpty());
    }

    @Test
    @DisplayName("E4 T-SM-07 重复事件幂等：SYNCED 行再 markPending 重开新事件 attempt 归零")
    void resyncAfterSyncedReopensEvent() {
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markSynced("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1b");
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.PENDING.name(), row.getSyncStatus());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-SM-08 retryOne 语义：无行/已 SYNCED/未注册处理器三类 no-op")
    void retryOneNoOpPaths() {
        assertEquals("NO_SUCH_SYNC_ROW",
                service.retryOne("d40t-none", SyncOperation.DELETE, "v1"));
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markSynced("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        assertEquals("NO_OP_ALREADY_SYNCED",
                service.retryOne("d40t-doc-1", SyncOperation.DELETE, "v1"));
        service.markPending("d40t-doc-4", SyncOperation.DELETE, "v1", "h4");
        assertEquals("NO_HANDLER_REGISTERED",
                service.retryOne("d40t-doc-4", SyncOperation.DELETE, "v1"));
    }

    @Test
    @DisplayName("E1 T-SM-09 FAILED 行处理器重试成功 → SYNCED（修复走管线）")
    void retrySucceedsConvergesSynced() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        String outcome = service.retryOne("d40t-doc-1", SyncOperation.DELETE, "v1");
        assertEquals(SyncStatus.SYNCED.name(), outcome);
    }

    @Test
    @DisplayName("E3 T-SM-10 处理器抛异常且未自归因：状态机兜底落库分类原因码")
    void retryThrowsFallbackClassified() {
        service.registerHandler(SyncOperation.DELETE,
                handler(SyncOperation.DELETE, false));
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                SyncReasonCode.CONFIRM_MISMATCH, "h1");
        String outcome = service.retryOne("d40t-doc-1", SyncOperation.DELETE, "v1");
        assertEquals(SyncStatus.FAILED.name(), outcome);
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        // 处理器抛出非连接类异常，状态机兜底归因必须重分类为 PG_DELETE_FAILED（而非沿用旧码）
        assertEquals(SyncReasonCode.PG_DELETE_FAILED, row.getReasonCode());
        assertEquals(2, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-SM-11 RETRY_EXHAUSTED 手动重试：计数重置后重放可收敛")
    void manualRetryResetsExhausted() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        service.markPending("d40t-doc-1", SyncOperation.DELETE, "v1", "h1");
        for (int i = 0; i < 3; i++) {
            service.markFailed("d40t-doc-1", SyncOperation.DELETE, "v1",
                    SyncReasonCode.PG_DELETE_FAILED, "h1");
        }
        String outcome = service.retryOne("d40t-doc-1", SyncOperation.DELETE, "v1");
        assertEquals(SyncStatus.SYNCED.name(), outcome);
        DocumentSyncRow row = service.find("d40t-doc-1", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-SM-12 classify 冻结口径：连接/超时/IO 精确类 → PG_UNREACHABLE，其余 → PG_DELETE_FAILED")
    void classifyFollowsFrozenMapping() {
        assertEquals(SyncReasonCode.PG_UNREACHABLE,
                DocumentSyncService.classify(new RuntimeException("Connect timed out")));
        assertEquals(SyncReasonCode.PG_UNREACHABLE,
                DocumentSyncService.classify(new RuntimeException("failed",
                        new java.io.IOException("stream closed"))));
        assertEquals(SyncReasonCode.PG_DELETE_FAILED,
                DocumentSyncService.classify(new RuntimeException("constraint violated")));
    }

    /** 测试处理器：成功路径按管线契约回调 markSynced；失败路径抛未归因异常（验证兜底）。 */
    private SyncRetryHandler handler(SyncOperation operation, boolean succeed) {
        return new SyncRetryHandler() {
            @Override
            public SyncOperation operation() {
                return operation;
            }

            @Override
            public void execute(DocumentSyncRow row) {
                if (!succeed) {
                    throw new RuntimeException("constraint violated");
                }
                service.markSynced(row.getDocumentId(), operation,
                        row.getVersion(), row.getPayloadHash());
            }
        };
    }

    /** 内存假仓储：仅替代存储层，不改变状态机判定。 */
    private static final class InMemorySyncRepository extends DocumentSyncRepository {

        private final Map<String, DocumentSyncRow> rows = new LinkedHashMap<>();

        InMemorySyncRepository() {
            super(null);
        }

        @Override
        public void upsertOnNaturalKey(DocumentSyncRow row) {
            row.setUpdatedAt(LocalDateTime.now());
            rows.put(key(row.getDocumentId(), SyncOperation.valueOf(row.getOperation()),
                    row.getVersion()), copy(row));
        }

        @Override
        public Optional<DocumentSyncRow> find(String documentId, String operation,
                                              String version) {
            DocumentSyncRow row = rows.get(documentId + "|" + operation + "|"
                    + (version == null ? "" : version));
            return Optional.ofNullable(row).map(InMemorySyncRepository::copy);
        }

        @Override
        public List<DocumentSyncRow> findByDocument(String documentId) {
            List<DocumentSyncRow> out = new ArrayList<>();
            rows.values().stream().filter(r -> r.getDocumentId().equals(documentId))
                    .forEach(r -> out.add(copy(r)));
            return out;
        }

        @Override
        public List<DocumentSyncRow> findRecent(String documentId, int limit) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<DocumentSyncRow> findDueForRetry(int maxAttempts, LocalDateTime now) {
            List<DocumentSyncRow> out = new ArrayList<>();
            for (DocumentSyncRow row : rows.values()) {
                if (SyncStatus.FAILED.name().equals(row.getSyncStatus())
                        && row.getAttemptCount() < maxAttempts
                        && row.getNextRetryAt() != null
                        && !row.getNextRetryAt().isAfter(now)) {
                    out.add(copy(row));
                }
            }
            return out;
        }

        @Override
        public List<DocumentSyncRow> findStuck(int stuckMinutes) {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckMinutes);
            List<DocumentSyncRow> out = new ArrayList<>();
            for (DocumentSyncRow row : rows.values()) {
                if (!SyncStatus.SYNCED.name().equals(row.getSyncStatus())
                        && row.getUpdatedAt() != null && row.getUpdatedAt().isBefore(cutoff)) {
                    out.add(copy(row));
                }
            }
            return out;
        }

        @Override
        public int updateState(String documentId, String operation, String version,
                               String status, String reasonCode, int attemptCount,
                               LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
            String key = documentId + "|" + operation + "|" + (version == null ? "" : version);
            DocumentSyncRow row = rows.get(key);
            if (row == null) {
                return 0;
            }
            row.setSyncStatus(status);
            row.setReasonCode(reasonCode);
            row.setAttemptCount(attemptCount);
            row.setNextRetryAt(nextRetryAt);
            row.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public long countByDocumentPrefix(String prefix) {
            return rows.values().stream()
                    .filter(r -> r.getDocumentId().startsWith(prefix)).count();
        }

        @Override
        public int deleteByDocument(String documentId) {
            int before = rows.size();
            rows.values().removeIf(r -> r.getDocumentId().equals(documentId));
            return before - rows.size();
        }

        private static DocumentSyncRow copy(DocumentSyncRow src) {
            DocumentSyncRow dst = new DocumentSyncRow();
            dst.setDocumentId(src.getDocumentId());
            dst.setOperation(src.getOperation());
            dst.setVersion(src.getVersion());
            dst.setSyncStatus(src.getSyncStatus());
            dst.setReasonCode(src.getReasonCode());
            dst.setAttemptCount(src.getAttemptCount());
            dst.setNextRetryAt(src.getNextRetryAt());
            dst.setPayloadHash(src.getPayloadHash());
            dst.setCreatedAt(src.getCreatedAt());
            dst.setUpdatedAt(src.getUpdatedAt());
            return dst;
        }
    }
}
