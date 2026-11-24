package com.zl.d41.knowledge.vectorindex.service;

import com.zl.d41.knowledge.chunking.ChunkBoundaryType;
import com.zl.d41.knowledge.chunking.ChunkSource;
import com.zl.d41.knowledge.chunking.ChunkStrategy;
import com.zl.d41.knowledge.chunking.DocumentChunk;
import com.zl.d41.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d41.knowledge.sync.DocumentSyncRepository;
import com.zl.d41.knowledge.sync.DocumentSyncRow;
import com.zl.d41.knowledge.table.TableRecordService;
import com.zl.d41.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d41.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d41.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d41.knowledge.vectorindex.model.VectorIndexStatus;
import com.zl.d41.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d41.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d41.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d41.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d41.knowledge.vectorstore.PgVectorAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

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

    /** R01：同步状态机与文档权威指针，用于版本漂移/卡死检测（只读，不改写）。 */
    private final DocumentSyncRepository syncRepository;

    private final KnowledgeDocumentRepository documentRepository;

    /**
     * D41 追加式：d41_table_extract/row 孤儿检出（TABLE_ORPHAN）。
     * ObjectProvider 缺席（table.enabled=false 或测试手动装配=null）时零检出零差异（H4）。
     */
    @Autowired(required = false)
    private ObjectProvider<TableRecordService> tableRecordProvider;

    public VectorReconciliationService(VectorStore vectorStore,
                                       PgVectorAccess pgVectorAccess,
                                       VectorChunkRepository repository,
                                       DocumentVectorIndexService indexService,
                                       VectorIndexProperties properties,
                                       DocumentSyncRepository syncRepository,
                                       KnowledgeDocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.pgVectorAccess = pgVectorAccess;
        this.repository = repository;
        this.indexService = indexService;
        this.properties = properties;
        this.syncRepository = syncRepository;
        this.documentRepository = documentRepository;
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

        // 5) R01：版本指针漂移（DRIFT_VERSION_MISMATCH）
        //    5a 权威口径：d41_vector_index_task.status=COMPLETED 的 active 版本
        //       vs knowledge_document.version（SWITCH 同事务更新，脱节=外部篡改/半途修改）
        Map<String, String> activeVersionByDoc = new LinkedHashMap<>();
        for (VectorIndexTaskRow row : tasks) {
            if (VectorIndexStatus.COMPLETED.name().equals(row.getStatus())) {
                activeVersionByDoc.put(row.getDocumentId(), row.getVersion());
            }
        }
        for (Map.Entry<String, String> entry : activeVersionByDoc.entrySet()) {
            String documentId = entry.getKey();
            String active = entry.getValue();
            documentRepository.findByDocumentId(documentId).ifPresent(doc -> {
                if (doc.getVersion() != null && !doc.getVersion().equals(active)) {
                    report.getVersionMismatches().add(new ReconciliationReportView.Issue(
                            "POINTER:" + documentId, documentId, active,
                            "knowledge_document.version=" + doc.getVersion() + " ≠ active=" + active));
                }
            });
            // 5b 同文档多 COMPLETED 版本并存（删除闭环应将其 SUPERSEDED，并存=半途漂移）
            long completedOfDoc = tasks.stream()
                    .filter(t -> documentId.equals(t.getDocumentId())
                            && VectorIndexStatus.COMPLETED.name().equals(t.getStatus()))
                    .count();
            if (completedOfDoc > 1) {
                report.getVersionMismatches().add(new ReconciliationReportView.Issue(
                        "MULTI-COMPLETED:" + documentId, documentId, active,
                        "同一文档存在 %d 个 COMPLETED 版本（应唯一）".formatted(completedOfDoc)));
            }
        }
        // 5c 同一 chunkId 两端版本不一致：PG 行 version vs MySQL 关联行 version
        //    （脱节=半途修改/外部直写库；PgVectorRow 仅暴露 id/documentId/version 直列）
        for (PgVectorAccess.PgVectorRow row : pgRows) {
            VectorChunkRow relation = relationByChunkId.get(row.id());
            if (relation != null && row.version() != null && relation.getVersion() != null
                    && !row.version().equals(relation.getVersion())) {
                report.getVersionMismatches().add(new ReconciliationReportView.Issue(
                        row.id(), row.documentId(), row.version(),
                        "PG 行 version=" + row.version() + " ≠ 关联行 version="
                                + relation.getVersion()));
            }
        }

        // 6) R01：同步状态机卡死（DRIFT_SYNC_STUCK；修复通道=同步重试管线，禁止直改 SQL）
        LocalDateTime syncCutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        for (DocumentSyncRow row : syncRepository.findStuck(properties.getStuckMinutes())) {
            report.getSyncStuck().add(new ReconciliationReportView.Issue(
                    row.getOperation() + ":" + row.getDocumentId() + ":" + row.getVersion(),
                    row.getDocumentId(), row.getVersion(),
                    "同步记录停留在 %s 超过 %d 分钟（reason=%s, attemptCount=%d, nextRetryAt=%s）"
                            .formatted(row.getSyncStatus(), properties.getStuckMinutes(),
                                    row.getReasonCode(), row.getAttemptCount(), row.getNextRetryAt())));
            if (!dryRun && row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(syncCutoff)) {
                report.getActionsTaken().add("SYNC_STUCK 收敛交由同步重试管线（recoverStuck 周期扫描），"
                        + "不在核查服务内直改: " + row.getDocumentId());
            }
        }

        // 7) D41 追加式：表格结构化记录孤儿检出（TABLE_ORPHAN；修复通道=删除闭环/重抽管线，H4 禁直改 SQL）
        TableRecordService tableService = tableRecordProvider == null
                ? null : tableRecordProvider.getIfAvailable();
        if (tableService != null) {
            for (Map<String, Object> orphan : tableService.detectOrphanRecords()) {
                report.getTableOrphans().add(new ReconciliationReportView.Issue(
                        "TABLE:" + orphan.get("id"), String.valueOf(orphan.get("document_id")),
                        String.valueOf(orphan.get("version")),
                        "d41_table_extract id=%s page=%s table=%s 文档缺失或版本≠权威指针"
                                .formatted(orphan.get("id"), orphan.get("page_number"),
                                        orphan.get("table_index"))));
            }
            if (!report.getTableOrphans().isEmpty() && !dryRun) {
                report.getActionsTaken().add("TABLE_ORPHAN 不在核查内直删；修复通道=删除闭环或重抽取端点"
                        + "（共 %d 条）".formatted(report.getTableOrphans().size()));
            }
        }

        if (!dryRun) {
            applyCompensation(report, orphanIds, missingByVersion);
            if (!report.getVersionMismatches().isEmpty()) {
                report.getActionsTaken().add("版本指针漂移不在核查内直改；修复通道=重新导入/SWITCH 管线"
                        + "（共 %d 条）".formatted(report.getVersionMismatches().size()));
            }
        }
        log.info("核对完成 dryRun={} issues={} (orphan={}, missing={}, mismatch={}, stuck={}, version={}, syncStuck={}, tableOrphan={})",
                dryRun, report.totalIssues(), report.getOrphanVectors().size(),
                report.getMissingVectors().size(), report.getCountMismatches().size(),
                report.getStuckTasks().size(), report.getVersionMismatches().size(),
                report.getSyncStuck().size(), report.getTableOrphans().size());
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
