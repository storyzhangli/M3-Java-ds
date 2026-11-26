package com.zl.d43.knowledge.sync;

import java.time.LocalDateTime;
import java.util.*;

/**
 * D40 R03 共享内存同步仓储替身（仅替代存储层，CAS/条件更新语义与生产 SQL 逐条对齐）。
 *
 * <p>供真实调用链组合测试使用；不访问任何数据库（父类 JdbcTemplate 传 null 不触达）。
 * 与 DocumentSyncServiceTest 内部替身同语义，独立公共类便于跨包测试复用。</p>
 */
public class InMemoryDocumentSyncRepository extends DocumentSyncRepository {

    private final Map<String, DocumentSyncRow> rows = new LinkedHashMap<>();

    public InMemoryDocumentSyncRepository() {
        super(null);
    }

    private static String key(String documentId, String operation, String version) {
        return documentId + "|" + operation + "|" + (version == null ? "" : version);
    }

    @Override
    public void upsertOnNaturalKey(DocumentSyncRow row) {
        row.setUpdatedAt(LocalDateTime.now());
        rows.put(key(row.getDocumentId(), row.getOperation(), row.getVersion()), copy(row));
    }

    @Override
    public Optional<DocumentSyncRow> find(String documentId, String operation, String version) {
        return Optional.ofNullable(rows.get(key(documentId, operation, version)))
                .map(InMemoryDocumentSyncRepository::copy);
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
        DocumentSyncRow row = rows.get(key(documentId, operation, version));
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
    public int markPendingConditional(String documentId, String operation, String version,
                                      int attemptCount, LocalDateTime now) {
        DocumentSyncRow row = rows.get(key(documentId, operation, version));
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
    public int claimForRetry(String documentId, String operation, String version,
                             String expectedStatus, int expectedAttempt,
                             LocalDateTime expectedNextRetryAt, boolean resetAttempt,
                             LocalDateTime now, LocalDateTime leaseUntil) {
        DocumentSyncRow row = rows.get(key(documentId, operation, version));
        if (row == null
                || !row.getSyncStatus().equals(expectedStatus)
                || row.getAttemptCount() != expectedAttempt
                || !Objects.equals(row.getNextRetryAt(), expectedNextRetryAt)) {
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

    @Override
    public long countByDocumentPrefix(String prefix) {
        return rows.values().stream().filter(r -> r.getDocumentId().startsWith(prefix)).count();
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
