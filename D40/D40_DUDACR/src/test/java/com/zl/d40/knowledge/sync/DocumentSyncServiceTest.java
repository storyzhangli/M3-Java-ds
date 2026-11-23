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

    // ------------------------------------------------------------------
    // R03-01 租约保护（T-R03-01~06）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T-R03-01 首次删除正常进入 PENDING（无租约、计数 0）")
    void firstOperationEntersPendingWithoutLease() {
        service.markPending("d40t-r03-01", SyncOperation.DELETE, "v1", "h1");
        DocumentSyncRow row = service.find("d40t-r03-01", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.PENDING.name(), row.getSyncStatus());
        assertNull(row.getNextRetryAt());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    @DisplayName("T-R03-02 FAILED 重试认领成功后租约不为空")
    void claimSetsNonEmptyLease() {
        java.util.concurrent.atomic.AtomicReference<LocalDateTime> leaseSeen =
                new java.util.concurrent.atomic.AtomicReference<>();
        service.registerHandler(SyncOperation.DELETE, new SyncRetryHandler() {
            @Override
            public SyncOperation operation() {
                return SyncOperation.DELETE;
            }

            @Override
            public void execute(DocumentSyncRow row) {
                leaseSeen.set(service.find(row.getDocumentId(), SyncOperation.DELETE,
                        row.getVersion()).orElseThrow().getNextRetryAt());
                service.markSynced(row.getDocumentId(), SyncOperation.DELETE,
                        row.getVersion(), row.getPayloadHash());
            }
        });
        service.markPending("d40t-r03-02", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-r03-02", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        assertEquals(SyncStatus.SYNCED.name(),
                service.retryOne("d40t-r03-02", SyncOperation.DELETE, "v1"));
        assertNotNull(leaseSeen.get(), "处理器执行期间必须持有有效租约");
        assertTrue(leaseSeen.get().isAfter(LocalDateTime.now()));
    }

    @Test
    @DisplayName("T-R03-03 Handler 内部重入 markPending 不清除租约、不清零计数")
    void reentrantMarkPendingKeepsLeaseAndAttempt() {
        service.registerHandler(SyncOperation.DELETE, new SyncRetryHandler() {
            @Override
            public SyncOperation operation() {
                return SyncOperation.DELETE;
            }

            @Override
            public void execute(DocumentSyncRow row) {
                // 模拟 deleteClosedLoop 步骤 0 重入业务初始化
                service.markPending(row.getDocumentId(), SyncOperation.DELETE,
                        row.getVersion(), row.getPayloadHash());
            }
        });
        service.markPending("d40t-r03-03", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-r03-03", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        service.retryOne("d40t-r03-03", SyncOperation.DELETE, "v1");
        DocumentSyncRow row = service.find("d40t-r03-03", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(SyncStatus.PENDING.name(), row.getSyncStatus());
        assertNotNull(row.getNextRetryAt(), "重入 markPending 不得清除 claim 租约");
        assertTrue(row.getNextRetryAt().isAfter(LocalDateTime.now()));
        assertEquals(1, row.getAttemptCount(), "重入不得清零历史失败计数");
    }

    @Test
    @DisplayName("T-R03-04 第二个执行者不能认领有效租约中的事件（快速拒绝+CAS 双闸）")
    void secondExecutorCannotClaimLeasedEvent() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        service.markPending("d40t-r03-04", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-r03-04", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        DocumentSyncRow snapshot =
                service.find("d40t-r03-04", SyncOperation.DELETE, "v1").orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        // 执行者 A 以旧快照认领成功
        assertEquals(1, repository.claimForRetry("d40t-r03-04", "DELETE", "v1",
                snapshot.getSyncStatus(), snapshot.getAttemptCount(), snapshot.getNextRetryAt(),
                false, now, now.plusSeconds(30)));
        // 执行者 B 经 retryOne 快速拒绝
        assertEquals("NO_OP_LEASED",
                service.retryOne("d40t-r03-04", SyncOperation.DELETE, "v1"));
        // 执行者 B 持同一旧快照直接 CAS 亦失败
        assertEquals(0, repository.claimForRetry("d40t-r03-04", "DELETE", "v1",
                snapshot.getSyncStatus(), snapshot.getAttemptCount(), snapshot.getNextRetryAt(),
                false, now, now.plusSeconds(30)));
    }

    @Test
    @DisplayName("T-R03-05 处理成功后状态 SYNCED（原因清空、计数归零、租约清空）")
    void successConvergesSyncedFully() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        service.markPending("d40t-r03-05", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-r03-05", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_UNREACHABLE, "h1");
        assertEquals(SyncStatus.SYNCED.name(),
                service.retryOne("d40t-r03-05", SyncOperation.DELETE, "v1"));
        DocumentSyncRow row = service.find("d40t-r03-05", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals("", row.getReasonCode());
        assertEquals(0, row.getAttemptCount());
        assertNull(row.getNextRetryAt());
    }

    @Test
    @DisplayName("T-R03-06 Handler 已 markFailed 后抛异常：失败计数只增加一次")
    void handlerRecordedFailureNotDoubleCounted() {
        service.registerHandler(SyncOperation.DELETE, new SyncRetryHandler() {
            @Override
            public SyncOperation operation() {
                return SyncOperation.DELETE;
            }

            @Override
            public void execute(DocumentSyncRow row) {
                service.markFailed(row.getDocumentId(), SyncOperation.DELETE, row.getVersion(),
                        SyncReasonCode.PG_DELETE_FAILED, row.getPayloadHash());
                throw new RuntimeException("pg delete failed after recording");
            }
        });
        service.markPending("d40t-r03-06", SyncOperation.DELETE, "v1", "h1");
        service.markFailed("d40t-r03-06", SyncOperation.DELETE, "v1",
                SyncReasonCode.PG_DELETE_FAILED, "h1");
        assertEquals(SyncStatus.FAILED.name(),
                service.retryOne("d40t-r03-06", SyncOperation.DELETE, "v1"));
        DocumentSyncRow row = service.find("d40t-r03-06", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(2, row.getAttemptCount(), "一次真实失败只允许 +1（1→2，而非 1→3）");
        assertEquals(SyncReasonCode.PG_DELETE_FAILED, row.getReasonCode());
    }

    // ------------------------------------------------------------------
    // R03-02 PENDING 租约超时恢复（T-R03-07~12）
    // ------------------------------------------------------------------

    /** 构造"认领后崩溃"现场：PENDING + 指定租约 + 指定 updatedAt（attempt 保留）。 */
    private void crashPending(String docId, int attempt, LocalDateTime lease,
                              LocalDateTime updatedAt) {
        service.markPending(docId, SyncOperation.DELETE, "v1", "h1");
        repository.updateState(docId, "DELETE", "v1", SyncStatus.PENDING.name(), "",
                attempt, lease, updatedAt);
    }

    @Test
    @DisplayName("T-R03-07 未过期 PENDING 不会被自动恢复")
    void freshLeasePendingNotRecovered() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        crashPending("d40t-r03-07", 1, LocalDateTime.now().plusSeconds(30), LocalDateTime.now());
        assertTrue(service.retryDue().isEmpty());
        assertEquals(SyncStatus.PENDING.name(),
                service.find("d40t-r03-07", SyncOperation.DELETE, "v1").orElseThrow()
                        .getSyncStatus());
    }

    @Test
    @DisplayName("T-R03-08 过期 PENDING 能被重新认领（CAS 1 行）")
    void expiredLeasePendingReclaimable() {
        LocalDateTime expired = LocalDateTime.now().minusSeconds(1);
        crashPending("d40t-r03-08", 1, expired, LocalDateTime.now().minusMinutes(1));
        DocumentSyncRow snapshot =
                service.find("d40t-r03-08", SyncOperation.DELETE, "v1").orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        assertEquals(1, repository.claimForRetry("d40t-r03-08", "DELETE", "v1",
                snapshot.getSyncStatus(), snapshot.getAttemptCount(), snapshot.getNextRetryAt(),
                false, now, now.plusSeconds(30)));
    }

    @Test
    @DisplayName("T-R03-09 两个恢复执行者竞争同一过期 PENDING：仅一个成功")
    void concurrentRecoveryOnlyOneWins() {
        LocalDateTime expired = LocalDateTime.now().minusSeconds(1);
        crashPending("d40t-r03-09", 2, expired, LocalDateTime.now().minusMinutes(1));
        DocumentSyncRow snapshot =
                service.find("d40t-r03-09", SyncOperation.DELETE, "v1").orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        int first = repository.claimForRetry("d40t-r03-09", "DELETE", "v1",
                snapshot.getSyncStatus(), snapshot.getAttemptCount(), snapshot.getNextRetryAt(),
                false, now, now.plusSeconds(30));
        int second = repository.claimForRetry("d40t-r03-09", "DELETE", "v1",
                snapshot.getSyncStatus(), snapshot.getAttemptCount(), snapshot.getNextRetryAt(),
                false, now, now.plusSeconds(30));
        assertEquals(1, first);
        assertEquals(0, second);
    }

    @Test
    @DisplayName("T-R03-10 恢复不清零 attemptCount（认领期间计数保留）")
    void recoveryKeepsAttemptCount() {
        java.util.concurrent.atomic.AtomicInteger attemptSeen =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        service.registerHandler(SyncOperation.DELETE, new SyncRetryHandler() {
            @Override
            public SyncOperation operation() {
                return SyncOperation.DELETE;
            }

            @Override
            public void execute(DocumentSyncRow row) {
                attemptSeen.set(service.find(row.getDocumentId(), SyncOperation.DELETE,
                        row.getVersion()).orElseThrow().getAttemptCount());
                service.markSynced(row.getDocumentId(), SyncOperation.DELETE,
                        row.getVersion(), row.getPayloadHash());
            }
        });
        crashPending("d40t-r03-10", 2, LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now().minusMinutes(1));
        assertEquals(SyncStatus.SYNCED.name(),
                service.retryOne("d40t-r03-10", SyncOperation.DELETE, "v1"));
        assertEquals(2, attemptSeen.get(), "恢复认领不得清零历史失败计数");
    }

    @Test
    @DisplayName("T-R03-11 过期 PENDING 经自动扫描恢复后达到 SYNCED")
    void recoveryViaSchedulerReachesSynced() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, true));
        crashPending("d40t-r03-11", 1, LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now().minusMinutes(1));
        List<String> results = service.retryDue();
        assertEquals(1, results.size());
        assertTrue(results.get(0).endsWith("=SYNCED"));
        assertEquals(SyncStatus.SYNCED.name(),
                service.find("d40t-r03-11", SyncOperation.DELETE, "v1").orElseThrow()
                        .getSyncStatus());
    }

    @Test
    @DisplayName("T-R03-12 恢复中再次失败：计数 +1 且达上限收敛 RETRY_EXHAUSTED")
    void recoveryFailureCountsCorrectly() {
        service.registerHandler(SyncOperation.DELETE, handler(SyncOperation.DELETE, false));
        crashPending("d40t-r03-12", 2, LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now().minusMinutes(1));
        assertEquals(SyncStatus.FAILED.name(),
                service.retryOne("d40t-r03-12", SyncOperation.DELETE, "v1"));
        DocumentSyncRow row = service.find("d40t-r03-12", SyncOperation.DELETE, "v1").orElseThrow();
        assertEquals(3, row.getAttemptCount(), "恢复后真实失败 2→3，只 +1");
        assertEquals(SyncReasonCode.RETRY_EXHAUSTED, row.getReasonCode());
        assertNull(row.getNextRetryAt());
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
        public List<DocumentSyncRow> findDueForRetry(int maxAttempts, LocalDateTime now,
                                                     LocalDateTime stalePendingCutoff) {
            List<DocumentSyncRow> out = new ArrayList<>();
            for (DocumentSyncRow row : rows.values()) {
                boolean failedDue = SyncStatus.FAILED.name().equals(row.getSyncStatus())
                        && !SyncReasonCode.RETRY_EXHAUSTED.equals(row.getReasonCode())
                        && row.getAttemptCount() < maxAttempts
                        && (row.getNextRetryAt() == null || !row.getNextRetryAt().isAfter(now));
                boolean pendingExpired = SyncStatus.PENDING.name().equals(row.getSyncStatus())
                        && row.getAttemptCount() < maxAttempts
                        && ((row.getNextRetryAt() != null && !row.getNextRetryAt().isAfter(now))
                                || (row.getNextRetryAt() == null && row.getUpdatedAt() != null
                                        && !row.getUpdatedAt().isAfter(stalePendingCutoff)));
                if (failedDue || pendingExpired) {
                    out.add(copy(row));
                }
            }
            return out;
        }

        /** R03-03：真实 CAS 语义替身——自然键+状态+计数+null 安全租约比较，全匹配才推进。 */
        @Override
        public int claimForRetry(String documentId, String operation, String version,
                                 String expectedStatus, int expectedAttempt,
                                 LocalDateTime expectedNextRetryAt, boolean resetAttempt,
                                 LocalDateTime now, LocalDateTime leaseUntil) {
            DocumentSyncRow row = rows.get(documentId + "|" + operation + "|"
                    + (version == null ? "" : version));
            if (row == null
                    || !row.getSyncStatus().equals(expectedStatus)
                    || row.getAttemptCount() != expectedAttempt
                    || !java.util.Objects.equals(row.getNextRetryAt(), expectedNextRetryAt)) {
                return 0;
            }
            row.setSyncStatus(SyncStatus.PENDING.name());
            row.setReasonCode("");
            if (resetAttempt) {
                row.setAttemptCount(0);
            }
            row.setNextRetryAt(leaseUntil);
            row.setUpdatedAt(now);
            return 1;
        }

        /** R03-01：条件化 PENDING 初始化——有效租约中的 PENDING 返回 0，不清租约。 */
        @Override
        public int markPendingConditional(String documentId, String operation, String version,
                                          int attemptCount, LocalDateTime now) {
            DocumentSyncRow row = rows.get(documentId + "|" + operation + "|"
                    + (version == null ? "" : version));
            if (row == null) {
                return 0;
            }
            if (SyncStatus.PENDING.name().equals(row.getSyncStatus())
                    && row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(now)) {
                return 0;
            }
            if (!SyncStatus.PENDING.name().equals(row.getSyncStatus())) {
                row.setNextRetryAt(null);
            }
            row.setSyncStatus(SyncStatus.PENDING.name());
            row.setReasonCode("");
            row.setAttemptCount(attemptCount);
            row.setUpdatedAt(now);
            return 1;
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
