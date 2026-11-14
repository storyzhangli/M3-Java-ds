package com.zl.d31.knowledge.vectorindex.service;

import com.zl.d31.knowledge.vectorindex.dto.ReconciliationReportView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时一致性核对任务：默认关闭，开启后按配置 Cron 执行非 dryRun 核对与补偿。
 *
 * <p>异常只记录日志，不抛出：单次失败不应终止后续调度。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.vector-index.reconcile-enabled", havingValue = "true")
public class VectorIndexReconcileScheduler {

    private final VectorReconciliationService reconciliationService;

    public VectorIndexReconcileScheduler(VectorReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${app.vector-index.reconcile-cron}")
    public void reconcile() {
        try {
            ReconciliationReportView report = reconciliationService.reconcile(false);
            log.info("定时核对完成 issues={} actions={}",
                    report.totalIssues(), report.getActionsTaken());
        } catch (RuntimeException ex) {
            log.warn("定时核对失败，等待下一周期异常类型={}",
                    ex.getClass().getSimpleName());
        }
    }
}
