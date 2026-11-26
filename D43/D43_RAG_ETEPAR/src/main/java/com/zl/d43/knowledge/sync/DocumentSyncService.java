package com.zl.d43.knowledge.sync;

import com.zl.d43.knowledge.sync.DocumentSyncRepository;
import com.zl.d43.knowledge.sync.SyncReasonCode;
import com.zl.d43.knowledge.sync.SyncRetryHandler;
import com.zl.d43.knowledge.vectorindex.config.VectorIndexProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 操作级同步状态机服务（R01 核心，§3.2 KP4 冻结）。
 *
 * <p>迁移：PENDING→SYNCED（双端确认）；PENDING→FAILED（原因码+退避）；
 * FAILED→PENDING（重试，attempt+1）；FAILED∧attempt≥maxAttempts→
 * 以 reason_code=RETRY_EXHAUSTED 表达待人工（不新增第四状态）。</p>
 *
 * <p>执行动作与状态机解耦：具体收敛动作由管线注册的 {@link SyncRetryHandler} 承担；
 * 手动 retry 对 RETRY_EXHAUSTED 行先重置尝试计数（ADMIN 权限通道，H7）。</p>
 */
@Slf4j
@Service
public class DocumentSyncService {

    private static final long MAX_BACKOFF_MS = 300_000L;

    private final DocumentSyncRepository repository;

    private final VectorIndexProperties properties;

    private final Map<SyncOperation, SyncRetryHandler> handlers =
            new EnumMap<>(SyncOperation.class);

    public DocumentSyncService(DocumentSyncRepository repository,
                               VectorIndexProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 管线注册收敛处理器（同操作重复注册以最后一次为准，幂等语义由实现方保证）。 */
    public void registerHandler(SyncOperation operation, SyncRetryHandler handler) {
        handlers.put(operation, handler);
    }

    // ------------------------------------------------------------------
    // 标记（P2 主链路的同步起点）
    // ------------------------------------------------------------------

    /**
     * 标记操作开始：PENDING。
     *
     * <p>R02-01 A 语义修正：attemptCount=该同步事件已发生的失败次数，
     * 开始重试不得清零。仅当既有行为 SYNCED（上一事件已收敛，本次为新事件，
     * 如新版本导入/重复删除）时归零；PENDING（重试管线预置）与 FAILED
     * （事件重开）均保留原计数，防止 retryOne→handler→markPending 链路
     * 把历史失败次数抹掉。</p>
     *
     * <p>R03-01 租约保护：既有行经条件更新推进——若行处于有效执行租约中的
     * PENDING（已被其他执行者认领），条件更新 0 行，本方法幂等跳过，
     * 绝不清除 claim 写入的 next_retry_at 租约（deleteClosedLoop 重入场景）。</p>
     */
    public void markPending(String documentId, SyncOperation operation,
                            String version, String payloadHash) {
        try {
            Optional<DocumentSyncRow> existing =
                    repository.find(documentId, operation.name(), version);
            LocalDateTime now = LocalDateTime.now();
            if (existing.isPresent()) {
                DocumentSyncRow row = existing.get();
                int attempt = SyncStatus.SYNCED.name().equals(row.getSyncStatus())
                        ? 0 : row.getAttemptCount();
                int updated = repository.markPendingConditional(documentId, operation.name(),
                        version, attempt, now);
                if (updated == 0) {
                    log.info("PENDING 初始化跳过：事件处于有效执行租约中（并发保护）"
                            + " documentId={} operation={} version={}",
                            documentId, operation, version);
                }
            } else {
                DocumentSyncRow row = newRow(documentId, operation, version, payloadHash);
                row.setSyncStatus(SyncStatus.PENDING.name());
                repository.upsertOnNaturalKey(row);
            }
        } catch (RuntimeException ex) {
            // 标记失败本身不可吞：上抛由调用方归因 MARK_FAILED（H8 失败必落库）
            log.error("同步标记 PENDING 失败 documentId={} operation={} reason={}",
                    documentId, operation, ex.getMessage());
            throw ex;
        }
    }

    /** 双端确认通过 → SYNCED（原因码清空，行不存在时防御性插入 SYNCED，幂等）。 */
    public void markSynced(String documentId, SyncOperation operation,
                           String version, String payloadHash) {
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.updateState(documentId, operation.name(), version,
                SyncStatus.SYNCED.name(), "", 0, null, now);
        if (updated == 0) {
            DocumentSyncRow row = newRow(documentId, operation, version, payloadHash);
            row.setSyncStatus(SyncStatus.SYNCED.name());
            repository.upsertOnNaturalKey(row);
        }
        log.info("同步收敛 SYNCED documentId={} operation={} version={}",
                documentId, operation, version);
    }

    /**
     * 仅当自然键行存在时收敛 SYNCED（UPDATE_CLEANUP 成功路径专用：
     * 正常成功不产噪音行，只有此前失败的遗留行需要被抹平）。
     */
    public void markSyncedIfRowExists(String documentId, SyncOperation operation,
                                      String version, String payloadHash) {
        repository.updateState(documentId, operation.name(), version,
                SyncStatus.SYNCED.name(), "", 0, null, LocalDateTime.now());
    }

    /** 任一步失败 → FAILED + 原因码 + attempt+1 + 指数退避；达上限转 RETRY_EXHAUSTED。 */
    public void markFailed(String documentId, SyncOperation operation,
                           String version, String reasonCode, String payloadHash) {
        Optional<DocumentSyncRow> existing =
                repository.find(documentId, operation.name(), version);
        LocalDateTime now = LocalDateTime.now();
        int attempt = existing.map(DocumentSyncRow::getAttemptCount).orElse(0) + 1;
        String reason = attempt >= properties.getMaxAttempts()
                ? SyncReasonCode.RETRY_EXHAUSTED : reasonCode;
        LocalDateTime nextRetry = SyncReasonCode.RETRY_EXHAUSTED.equals(reason)
                ? null : now.plusNanos(Math.min(
                        properties.getRetryBackoffMs() * 1_000_000L << Math.min(attempt - 1, 9),
                        MAX_BACKOFF_MS * 1_000_000L));
        int updated = repository.updateState(documentId, operation.name(), version,
                SyncStatus.FAILED.name(), reason, attempt, nextRetry, now);
        if (updated == 0) {
            DocumentSyncRow row = newRow(documentId, operation, version, payloadHash);
            row.setSyncStatus(SyncStatus.FAILED.name());
            row.setReasonCode(reason);
            row.setAttemptCount(attempt);
            row.setNextRetryAt(nextRetry);
            repository.upsertOnNaturalKey(row);
        }
        log.warn("同步标记 FAILED documentId={} operation={} version={} reason={} attempt={}",
                documentId, operation, version, reason, attempt);
    }

    // ------------------------------------------------------------------
    // 重试（手动恒可用 / 调度器按到期扫描）
    // ------------------------------------------------------------------

    /**
     * 重试单条同步事件。
     *
     * <p>R02-01 语义修正（B/D）：</p>
     * <ul>
     *   <li>进入执行前必须经 {@code claimForRetry} 条件更新（CAS：状态+计数+租约三条件），
     *       并发或重复消费第二个执行者得 0 行，直接放弃，避免同一事件被多执行者同时处理；</li>
     *   <li>普通重试不清零 attemptCount；仅 RETRY_EXHAUSTED 的人工/调度恢复显式重置
     *       （人工恢复入口语义，与既有 H7 一致）；</li>
     *   <li>失败落账单一责任层：处理器能识别原因时自行 markFailed；外层仅在处理器
     *       抛异常且最新持久化状态仍为 PENDING（未落账）时兜底记录——绝不重复计数。</li>
     * </ul>
     *
     * <p>返回处理结果描述；SYNCED 行为 no-op（E4 重复事件幂等）。</p>
     */
    public String retryOne(String documentId, SyncOperation operation, String version) {
        Optional<DocumentSyncRow> found =
                repository.find(documentId, operation.name(), version);
        if (found.isEmpty()) {
            return "NO_SUCH_SYNC_ROW";
        }
        DocumentSyncRow row = found.get();
        if (SyncStatus.SYNCED.name().equals(row.getSyncStatus())) {
            return "NO_OP_ALREADY_SYNCED";
        }
        LocalDateTime now = LocalDateTime.now();
        // R03-01：有效租约中的 PENDING=已被其他执行者认领，快速拒绝（CAS 之外的第一道闸）
        if (SyncStatus.PENDING.name().equals(row.getSyncStatus())
                && row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(now)) {
            return "NO_OP_LEASED";
        }
        SyncRetryHandler handler = handlers.get(operation);
        if (handler == null) {
            return "NO_HANDLER_REGISTERED";
        }
        boolean manualReset = SyncReasonCode.RETRY_EXHAUSTED.equals(row.getReasonCode());
        if (manualReset) {
            log.info("RETRY_EXHAUSTED 恢复入口：重置尝试计数后重试 documentId={} operation={}",
                    documentId, operation);
        }
        // CAS claim：status/attempt/next_retry_at 三条件，0 行=并发竞争失败或状态已被推进
        LocalDateTime leaseUntil = now.plusNanos(leaseDurationNanos());
        int claimed = repository.claimForRetry(documentId, operation.name(), version,
                row.getSyncStatus(), row.getAttemptCount(), row.getNextRetryAt(),
                manualReset, now, leaseUntil);
        if (claimed == 0) {
            log.info("重试 claim 失败（并发消费或状态推进）documentId={} operation={} version={}",
                    documentId, operation, version);
            return "NO_OP_CONCURRENT_CLAIM";
        }
        DocumentSyncRow pending =
                repository.find(documentId, operation.name(), version).orElse(row);
        try {
            handler.execute(pending);
        } catch (RuntimeException ex) {
            // 单一责任层兜底：仅当处理器未落账（最新状态仍 PENDING）时外层记录
            String latestStatus = repository.find(documentId, operation.name(), version)
                    .map(DocumentSyncRow::getSyncStatus).orElse("");
            if (SyncStatus.PENDING.name().equals(latestStatus) || latestStatus.isEmpty()) {
                markFailed(documentId, operation, version, classify(ex), row.getPayloadHash());
                log.warn("重试处理器异常未落账，状态机兜底记录 documentId={} operation={} reason={}",
                        documentId, operation, classify(ex));
            } else {
                log.warn("重试处理器异常已由管线层落账，外层不重复计数 documentId={} operation={} "
                        + "latestStatus={} error={}", documentId, operation, latestStatus,
                        ex.getMessage());
            }
        }
        return repository.find(documentId, operation.name(), version)
                .map(latest -> latest.getSyncStatus()).orElse("UNKNOWN");
    }

    /**
     * 自动重试扫描（调度器入口；sync-retry.enabled=false 时永不被调度）。
     *
     * <p>R03-02：除到期 FAILED 外，同时恢复租约过期的 PENDING（认领后进程崩溃）；
     * 首建 null 租约 PENDING 以 updated_at + 租约时长宽限判定，避免误判正常执行。
     * 恢复复用 retryOne 同一 CAS 认领，多调度器竞争仅一个成功；计数保留不清零。</p>
     */
    public List<String> retryDue() {
        List<String> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime stalePendingCutoff = now.minusNanos(leaseDurationNanos());
        for (DocumentSyncRow row : repository.findDueForRetry(
                properties.getMaxAttempts(), now, stalePendingCutoff)) {
            results.add(row.getDocumentId() + "|" + row.getOperation()
                    + "|" + row.getVersion()
                    + "=" + retryOne(row.getDocumentId(),
                            SyncOperation.valueOf(row.getOperation()), row.getVersion()));
        }
        return results;
    }

    /** 执行租约时长（claim 租约与 null 租约 PENDING 宽限共用同一口径）。 */
    private long leaseDurationNanos() {
        return Math.max(properties.getRetryBackoffMs() * 8_000_000L, 5_000_000_000L);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<DocumentSyncRow> recent(String documentId, int limit) {
        return documentId == null || documentId.isBlank()
                ? repository.findRecent(null, limit)
                : repository.findRecent(documentId, limit);
    }

    public Optional<DocumentSyncRow> find(String documentId, SyncOperation operation,
                                          String version) {
        return repository.find(documentId, operation.name(), version);
    }

    /**
     * 异常归因（冻结口径）：连接/不可达/超时/套接字/IO 异常类 → PG_UNREACHABLE，其余 → PG_DELETE_FAILED。
     *
     * <p>R01 测试修正：不使用裸子串 "io"（会误命中 violated 等普通词，
     * 导致约束冲突被误判为连接类），IO 类以 ioexception / streamclosed 精确匹配。</p>
     */
    public static String classify(RuntimeException ex) {
        String message = (ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage())
                .toLowerCase(Locale.ROOT);
        String causeName = ex.getCause() == null ? "" : ex.getCause().getClass().getName()
                .toLowerCase(Locale.ROOT);
        if (message.contains("connect") || message.contains("unreachable")
                || message.contains("timeout") || message.contains("timed out")
                || message.contains("communication") || message.contains("socket")
                || message.contains("ioexception") || message.contains("streamclosed")
                || causeName.contains("ioexception") || causeName.contains("socket")) {
            return SyncReasonCode.PG_UNREACHABLE;
        }
        return SyncReasonCode.PG_DELETE_FAILED;
    }

    private DocumentSyncRow newRow(String documentId, SyncOperation operation,
                                   String version, String payloadHash) {
        DocumentSyncRow row = new DocumentSyncRow();
        row.setDocumentId(documentId);
        row.setOperation(operation.name());
        row.setVersion(version == null ? "" : version);
        row.setPayloadHash(payloadHash == null ? "" : payloadHash);
        row.setAttemptCount(0);
        return row;
    }
}
