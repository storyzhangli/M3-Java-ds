package com.zl.d40.knowledge.sync;

import com.zl.d40.knowledge.vectorindex.config.VectorIndexProperties;
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
 * 操作级同步状态机服务（D40 R01 核心，§3.2 KP4 冻结）。
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
     * 标记操作开始：PENDING。已存在 SYNCED 行视为新事件重开（attempt 归零）；
     * FAILED 行的重试推进由 retryOne 负责，此处不吞并其计数。
     */
    public void markPending(String documentId, SyncOperation operation,
                            String version, String payloadHash) {
        try {
            Optional<DocumentSyncRow> existing =
                    repository.find(documentId, operation.name(), version);
            LocalDateTime now = LocalDateTime.now();
            if (existing.isPresent()) {
                DocumentSyncRow row = existing.get();
                int attempt = SyncStatus.FAILED.name().equals(row.getSyncStatus())
                        ? row.getAttemptCount() : 0;
                repository.updateState(documentId, operation.name(), version,
                        SyncStatus.PENDING.name(), "", attempt, null, now);
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
     * <p>返回处理结果描述；SYNCED 行为 no-op（E4 重复事件幂等）；
     * RETRY_EXHAUSTED 行手动重试时先重置尝试计数（ADMIN 通道）。</p>
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
        if (SyncStatus.PENDING.name().equals(row.getSyncStatus())) {
            // PENDING 卡滞（标记后进程崩溃）：直接经处理器重放闭环
        }
        SyncRetryHandler handler = handlers.get(operation);
        if (handler == null) {
            return "NO_HANDLER_REGISTERED";
        }
        if (SyncReasonCode.RETRY_EXHAUSTED.equals(row.getReasonCode())) {
            repository.updateState(documentId, operation.name(), version,
                    SyncStatus.PENDING.name(), "", 0, null, LocalDateTime.now());
            log.info("RETRY_EXHAUSTED 手动重置并重试 documentId={} operation={}",
                    documentId, operation);
        } else {
            repository.updateState(documentId, operation.name(), version,
                    SyncStatus.PENDING.name(), "", row.getAttemptCount(), null,
                    LocalDateTime.now());
        }
        DocumentSyncRow pending =
                repository.find(documentId, operation.name(), version).orElse(row);
        try {
            handler.execute(pending);
        } catch (RuntimeException ex) {
            // 处理器未自行归因时的兜底：状态机不允许失败逃逸无痕
            markFailed(documentId, operation, version, classify(ex), row.getPayloadHash());
            log.warn("重试处理器异常已由状态机兜底落库 documentId={} operation={} reason={}",
                    documentId, operation, classify(ex));
        }
        return repository.find(documentId, operation.name(), version)
                .map(latest -> latest.getSyncStatus()).orElse("UNKNOWN");
    }

    /** 自动重试扫描（调度器入口；sync-retry.enabled=false 时永不被调度）。 */
    public List<String> retryDue() {
        List<String> results = new ArrayList<>();
        for (DocumentSyncRow row : repository.findDueForRetry(
                properties.getMaxAttempts(), LocalDateTime.now())) {
            results.add(row.getDocumentId() + "|" + row.getOperation()
                    + "|" + row.getVersion()
                    + "=" + retryOne(row.getDocumentId(),
                            SyncOperation.valueOf(row.getOperation()), row.getVersion()));
        }
        return results;
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
     * <p>D40 R01 测试修正：不使用裸子串 "io"（会误命中 violated 等普通词，
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
