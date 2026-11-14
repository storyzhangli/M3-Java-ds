package com.zl.d31.knowledge.vectorindex.service;

import com.zl.d31.knowledge.chunking.ChunkBoundaryType;
import com.zl.d31.knowledge.chunking.ChunkSource;
import com.zl.d31.knowledge.chunking.ChunkStrategy;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import com.zl.d31.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d31.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.knowledge.vectorindex.model.VectorIndexStatus;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d31.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d31.knowledge.vectorstore.PgVectorAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨系统一致性核对与补偿服务。
 *
 * <p>以真实数据库为唯一事实来源，核对 MySQL（关联/状态库）与 PostgreSQL（向量库）：
 * 孤儿向量、缺失向量、数量不一致、卡住/失败任务。dryRun 只出报告；非 dryRun 执行
 * 补偿删除（孤儿）、快照幂等重建（缺失）与任务恢复，不 Mock 数据库。</p>
 *
 * <p>不引入分布式事务：补偿动作复用 {@link DocumentVectorIndexService} 的
 * 稳定 ID 与 Upsert，保证重复执行安全。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class VectorReconciliationService {

    private final VectorStore vectorStore;

    private final PgVectorAccess pgVectorAccess;

    private final VectorChunkRepository repository;

    private final DocumentVectorIndexService indexService;

    private final VectorIndexProperties properties;

    public VectorReconciliationService(VectorStore vectorStore,
                                       PgVectorAccess pgVectorAccess,
                                       VectorChunkRepository repository,
                                       DocumentVectorIndexService indexService,
                                       VectorIndexProperties properties) {
        this.vectorStore = vectorStore;
        this.pgVectorAccess = pgVectorAccess;
        this.repository = repository;
        this.indexService = indexService;
        this.properties = properties;
    }

    /**
     * 执行核对。
     *
     * @param dryRun true 只出报告；false 执行补偿
     */
    public ReconciliationReportView reconcile(boolean dryRun) {
        ReconciliationReportView report = new ReconciliationReportView();
        report.setDryRun(dryRun);

        List<PgVectorAccess.PgVectorRow> pgRows = pgVectorAccess.listAll();
        List<VectorChunkRow> relations = repository.findAllChunks();
        List<VectorIndexTaskRow> tasks = repository.findAllTasks();
        report.setPgTotalRows(pgRows.size());
        report.setRelationRows(relations.size());
        report.setTaskRows(tasks.size());

        Map<String, VectorChunkRow> relationByChunkId = new HashMap<>();
        for (VectorChunkRow row : relations) {
            relationByChunkId.put(row.getChunkId(), row);
        }
        Map<String, VectorIndexTaskRow> taskByKey = new HashMap<>();
        for (VectorIndexTaskRow row : tasks) {
            taskByKey.put(taskKey(row.getDocumentId(), row.getVersion()), row);
        }

        // 1) 孤儿向量：PG 有，但无关联行，或所属任务不存在/非活动
        Set<String> pgIds = new HashSet<>();
        List<String> orphanIds = new ArrayList<>();
        for (PgVectorAccess.PgVectorRow row : pgRows) {
            pgIds.add(row.id());
            VectorChunkRow relation = relationByChunkId.get(row.id());
            VectorIndexTaskRow task = taskByKey.get(taskKey(row.documentId(), row.version()));
            if (relation == null || task == null || !isActive(task.getStatus())) {
                report.getOrphanVectors().add(new ReconciliationReportView.Issue(
                        row.id(), row.documentId(), row.version(),
                        relation == null ? "PG 向量无 MySQL 关联行" : "所属任务状态非活动"));
                orphanIds.add(row.id());
            }
        }

        // 2) 缺失向量：关联行存在但 PG 无对应行
        Map<String, List<VectorChunkRow>> missingByVersion = new LinkedHashMap<>();
        for (VectorChunkRow row : relations) {
            if (!pgIds.contains(row.getChunkId())) {
                report.getMissingVectors().add(new ReconciliationReportView.Issue(
                        row.getChunkId(), row.getDocumentId(), row.getVersion(),
                        "MySQL 关联行存在但 PG 缺失向量"));
                missingByVersion
                        .computeIfAbsent(taskKey(row.getDocumentId(), row.getVersion()),
                                key -> new ArrayList<>())
                        .add(row);
            }
        }

        // 3) 数量不一致：版本级关联数 vs PG 实际数
        Map<String, List<VectorChunkRow>> relationByVersion = new LinkedHashMap<>();
        for (VectorChunkRow row : relations) {
            relationByVersion
                    .computeIfAbsent(taskKey(row.getDocumentId(), row.getVersion()),
                            key -> new ArrayList<>())
                    .add(row);
        }
        for (Map.Entry<String, List<VectorChunkRow>> entry : relationByVersion.entrySet()) {
            List<VectorChunkRow> rows = entry.getValue();
            String documentId = rows.get(0).getDocumentId();
            String version = rows.get(0).getVersion();
            long pgCount = pgVectorAccess.countByDocumentVersion(documentId, version);
            if (pgCount != rows.size()) {
                report.getCountMismatches().add(new ReconciliationReportView.Issue(
                        entry.getKey(), documentId, version,
                        "关联数=%d 而 PG 数=%d".formatted(rows.size(), pgCount)));
            }
        }

        // 4) 卡住任务与可重试失败任务
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        for (VectorIndexTaskRow row : tasks) {
            boolean stuck = (VectorIndexStatus.PENDING.name().equals(row.getStatus())
                    || VectorIndexStatus.INDEXING.name().equals(row.getStatus()))
                    && row.getUpdatedAt() != null && row.getUpdatedAt().isBefore(cutoff);
            if (stuck) {
                report.getStuckTasks().add(new ReconciliationReportView.Issue(
                        taskKey(row.getDocumentId(), row.getVersion()),
                        row.getDocumentId(), row.getVersion(),
                        "任务停留在 " + row.getStatus() + " 超过 "
                                + properties.getStuckMinutes() + " 分钟"));
            }
        }

        if (!dryRun) {
            applyCompensation(report, orphanIds, missingByVersion);
        }
        log.info("核对完成 dryRun={} issues={} (orphan={}, missing={}, mismatch={}, stuck={})",
                dryRun, report.totalIssues(), report.getOrphanVectors().size(),
                report.getMissingVectors().size(), report.getCountMismatches().size(),
                report.getStuckTasks().size());
        return report;
    }

    private void applyCompensation(ReconciliationReportView report,
                                   List<String> orphanIds,
                                   Map<String, List<VectorChunkRow>> missingByVersion) {
        // 补偿删除孤儿向量（幂等：已不存在不报错）
        if (!orphanIds.isEmpty()) {
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(orphanIds));
            int batchSize = Math.max(1, properties.getBatchSize());
            for (int from = 0; from < distinct.size(); from += batchSize) {
                int to = Math.min(from + batchSize, distinct.size());
                vectorStore.delete(distinct.subList(from, to));
            }
            report.getActionsTaken().add("删除孤儿向量 " + distinct.size() + " 条");
        }

        // 补偿重建缺失向量：仅当 Outbox 快照完整时按稳定 ID 幂等重跑
        for (Map.Entry<String, List<VectorChunkRow>> entry : missingByVersion.entrySet()) {
            List<VectorChunkRow> missingRows = entry.getValue();
            String documentId = missingRows.get(0).getDocumentId();
            String version = missingRows.get(0).getVersion();
            List<VectorChunkRow> fullSnapshot = repository.findChunks(documentId, version);
            VectorIndexTaskRow task = repository.findTask(documentId, version).orElse(null);
            if (task == null || fullSnapshot.isEmpty()
                    || fullSnapshot.size() < task.getChunkCount()) {
                report.getActionsTaken().add(
                        "跳过重建 %s/%s：快照不完整".formatted(documentId, version));
                continue;
            }
            List<DocumentChunk> chunks = toChunks(fullSnapshot);
            indexService.indexChunks(documentId, version,
                    missingRows.get(0).getStrategy(), null, chunks);
            report.getActionsTaken().add(
                    "重建缺失向量 documentId=%s version=%s 缺失=%d"
                            .formatted(documentId, version, missingRows.size()));
        }

        // 重试失败任务与恢复卡住任务（内部同样以快照完整性为准）
        List<VectorIndexResultView> retried = indexService.retryFailedTasks();
        if (!retried.isEmpty()) {
            report.getActionsTaken().add("重试失败任务 " + retried.size() + " 个");
        }
        List<VectorIndexResultView> recovered = indexService.recoverStuckTasks();
        if (!recovered.isEmpty()) {
            report.getActionsTaken().add("恢复卡住任务 " + recovered.size() + " 个");
        }
    }

    private boolean isActive(String status) {
        return VectorIndexStatus.COMPLETED.name().equals(status)
                || VectorIndexStatus.INDEXING.name().equals(status)
                || VectorIndexStatus.PARTIAL.name().equals(status)
                || VectorIndexStatus.PENDING.name().equals(status);
    }

    private List<DocumentChunk> toChunks(List<VectorChunkRow> rows) {
        List<DocumentChunk> chunks = new ArrayList<>(rows.size());
        for (VectorChunkRow row : rows) {
            chunks.add(new DocumentChunk(
                    row.getDocumentId(),
                    null,
                    row.getVersion(),
                    row.getChunkIndex(),
                    ChunkStrategy.valueOf(row.getStrategy()),
                    row.getContent(),
                    row.getCharacterCount(),
                    List.<Integer>of(),
                    List.<ChunkSource>of(),
                    // 快照未存边界类型，仅用于载荷重建，不参与稳定 ID
                    ChunkBoundaryType.HARD_LIMIT));
        }
        return chunks;
    }

    private String taskKey(String documentId, String version) {
        return documentId + "|" + version;
    }
}
