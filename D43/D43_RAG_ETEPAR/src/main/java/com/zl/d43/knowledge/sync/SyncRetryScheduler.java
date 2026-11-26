package com.zl.d43.knowledge.sync;

import com.zl.d43.knowledge.sync.DocumentSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 同步自动重试调度器（H2：默认关闭，手动重试端点恒可用）。
 *
 * <p>与既有 VectorIndexReconcileScheduler（索引任务恢复口径）正交：本调度器只收敛
 * 操作级 d43_document_sync FAILED 事件，周期复用 reconcile-cron 的错峰直觉——
 * 独立 fixedDelay（5 分钟），不依赖 reconcile.enabled。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.vector-index.sync-retry.enabled", havingValue = "true")
public class SyncRetryScheduler {

    private final DocumentSyncService syncService;

    public SyncRetryScheduler(DocumentSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    public void retryDueSyncEvents() {
        try {
            var results = syncService.retryDue();
            if (!results.isEmpty()) {
                log.info("同步自动重试完成 count={} results={}", results.size(), results);
            }
        } catch (RuntimeException ex) {
            // 调度异常不致命：FAILED 行仍在库，可查询可手动重试（失败必落库原则）
            log.warn("同步自动重试调度异常 reason={}", ex.getMessage());
        }
    }
}
