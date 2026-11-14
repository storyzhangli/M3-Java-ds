package com.zl.d31.knowledge.vectorindex.service;

import com.zl.d31.common.ResultCode;
import com.zl.d31.knowledge.chunking.ChunkBoundaryType;
import com.zl.d31.knowledge.chunking.ChunkSource;
import com.zl.d31.knowledge.chunking.ChunkStrategy;
import com.zl.d31.knowledge.chunking.ChunkingResult;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import com.zl.d31.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.vectorindex.VectorIndexException;
import com.zl.d31.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d31.knowledge.vectorindex.dto.ChunkIndexView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexTaskView;
import com.zl.d31.knowledge.vectorindex.model.ChunkRowStatus;
import com.zl.d31.knowledge.vectorindex.model.VectorIndexStage;
import com.zl.d31.knowledge.vectorindex.model.VectorIndexStatus;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d31.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d31.knowledge.vectorindex.support.ContentHasher;
import com.zl.d31.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d31.knowledge.vectorstore.PgVectorAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档向量索引编排服务：D31 核心。
 *
 * <p>在既有切分（{@link KnowledgeDocumentChunkingService}）、Embedding
 * （经 {@link VectorStore} 内部调用）与 PostgreSQL/pgvector 持久化能力之上，
 * 补齐工程化的幂等、版本切换、删除与恢复：</p>
 * <ul>
 *   <li>稳定 Chunk ID（UUID v5）+ 框架原生阿 Upsert（ON CONFLICT (id) DO UPDATE）
 *       + MySQL 唯一约束，重复导入与失败重试不产生重复数据；</li>
 *   <li>新版本先写后切（SWITCH），旧版本在新版本确认成功后才清理，
 *       避免“先删旧、再因新版本失败而全丢”；</li>
 *   <li>按文档或版本删除分批执行且可重复执行，PG 对不存在 id 不报错；</li>
 *   <li>失败落账（状态/阶段/错误/尝试次数）与基于 Outbox 快照的幂等恢复。</li>
 * </ul>
 *
 * <p>不使用跨库分布式事务：MySQL 与 PostgreSQL 各自本地原子，跨系统一致性由
 * 状态机 + 核对补偿（{@code VectorReconciliationService}）收敛。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class DocumentVectorIndexService {

    private static final int MAX_ERROR_MESSAGE = 480;

    private final VectorStore vectorStore;

    private final PgVectorAccess pgVectorAccess;

    private final VectorChunkRepository repository;

    private final StableChunkIdGenerator idGenerator;

    private final ContentHasher hasher;

    private final VectorIndexProperties properties;

    private final EmbeddingModelDescriptor descriptor;

    private final KnowledgeDocumentChunkingService chunkingService;

    private final TransactionTemplate transactionTemplate;

    public DocumentVectorIndexService(VectorStore vectorStore,
                                      PgVectorAccess pgVectorAccess,
                                      VectorChunkRepository repository,
                                      StableChunkIdGenerator idGenerator,
                                      ContentHasher hasher,
                                      VectorIndexProperties properties,
                                      EmbeddingModelDescriptor descriptor,
                                      KnowledgeDocumentChunkingService chunkingService,
                                      PlatformTransactionManager transactionManager) {
        this.vectorStore = vectorStore;
        this.pgVectorAccess = pgVectorAccess;
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.hasher = hasher;
        this.properties = properties;
        this.descriptor = descriptor;
        this.chunkingService = chunkingService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // 入口：从已解析页面切分并入库（真实链路）
    // ------------------------------------------------------------------

    /**
     * 从知识文档的页面级文本重新切分，并将 Chunk 幂等写入向量库。
     *
     * <p>文档必须已完成解析（COMPLETED），版本取知识文档登记版本。</p>
     */
    public VectorIndexResultView indexFromPages(String documentId, String strategy) {
        requireDocumentId(documentId);
        ChunkStrategy targetStrategy = resolveStrategy(strategy);
        ChunkingResult chunking = chunkingService.preview(documentId, targetStrategy);
        return indexChunks(chunking.documentId(), chunking.version(),
                chunking.strategy().name(), chunking.title(), chunking.chunks());
    }

    // ------------------------------------------------------------------
    // 核心：幂等入库（新增 / 重复导入 / 同版本覆盖 / 新版本切换）
    // ------------------------------------------------------------------

    /**
     * 对给定 Chunk 集合执行幂等向量化入库。
     *
     * <p>幂等键为 (documentId, version) 任务行 + 稳定 chunkId；重复执行同一输入不会新增
     * 任何行，同版本内容变化按稳定 ID 覆盖并清理旧行，新版本先写后切换。</p>
     *
     * @param documentId 文档业务标识
     * @param version    文档版本，空则默认 "1"
     * @param strategy   切分策略，空则使用配置默认值
     * @param title      标题（空则沿用 Chunk 自带标题）
     * @param chunks     待索引 Chunk
     */
    public VectorIndexResultView indexChunks(String documentId, String version,
                                             String strategy, String title,
                                             List<DocumentChunk> chunks) {
        requireDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待索引 Chunk 不能为空");
        }
        String targetVersion = version == null || version.isBlank() ? "1" : version.strip();
        ChunkStrategy targetStrategy = resolveStrategy(strategy);
        List<DocumentChunk> normalized = normalize(
                documentId, targetVersion, targetStrategy, title, chunks);
        String payloadHash = hasher.hash(payloadText(normalized));

        VectorIndexTaskRow existing = repository.findTask(documentId, targetVersion).orElse(null);
        // 迟到任务保护：已被取代的旧版本不得在更新版本活动期间回写，避免版本回滚
        if (existing != null && VectorIndexStatus.SUPERSEDED.name().equals(existing.getStatus())) {
            boolean hasNewerActive = repository.findTasksByDocument(documentId).stream()
                    .anyMatch(row -> !targetVersion.equals(row.getVersion())
                            && VectorIndexStatus.COMPLETED.name().equals(row.getStatus()));
            if (hasNewerActive) {
                throw new VectorIndexException(ResultCode.VECTOR_INDEX_STATE_CONFLICT,
                        "版本 " + targetVersion + " 已被更新版本取代，拒绝迟到写入；"
                                + "如确需回滚，请先删除当前活动版本再重建",
                        null, "STALE_VERSION_REJECTED");
            }
        }
        if (existing != null && isIdempotentHit(existing, payloadHash, normalized.size())) {
            log.info("重复导入命中幂等，documentId={} version={} chunks={}",
                    documentId, targetVersion, normalized.size());
            return toResult(existing, normalized.size(), 0, true);
        }

        int attempt = existing == null ? 1 : existing.getAttemptCount() + 1;
        LocalDateTime now = LocalDateTime.now();
        List<String> previousChunkIds = repository.findChunks(documentId, targetVersion).stream()
                .map(VectorChunkRow::getChunkId)
                .toList();

        VectorIndexTaskRow task = existing == null ? new VectorIndexTaskRow() : existing;
        task.setDocumentId(documentId);
        task.setVersion(targetVersion);
        task.setStrategy(targetStrategy.name());
        task.setContentHash(payloadHash);
        task.setSplitVersion(properties.getSplitVersion());
        task.setVectorSpaceId(descriptor.vectorSpaceId());
        task.setChunkCount(normalized.size());
        task.setStatus(VectorIndexStatus.INDEXING.name());
        task.setStage(VectorIndexStage.REGISTER.name());
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setAttemptCount(attempt);
        task.setNextRetryAt(null);
        task.setUpdatedAt(now);
        if (existing == null) {
            task.setCreatedAt(now);
            // 并发首建由唯一键 (document_id, version) 兜底，Upsert 不会产生第二行
            repository.upsertTask(task);
        } else {
            repository.updateTaskState(task);
        }

        List<PreparedChunk> prepared = prepare(normalized);

        // 阶段 1：Outbox 快照完整落库（MySQL 本地事务）。
        // 先存快照再写向量：任何一步中断都能从完整快照按稳定 ID 幂等重建。
        LocalDateTime snapshotTime = LocalDateTime.now();
        List<VectorChunkRow> snapshotRows = new ArrayList<>(prepared.size());
        for (PreparedChunk pc : prepared) {
            snapshotRows.add(toChunkRow(pc, snapshotTime, ChunkRowStatus.PENDING.name()));
        }
        transactionTemplate.executeWithoutResult(status ->
                repository.upsertChunks(snapshotRows));

        int processed = 0;
        int batchSize = Math.max(1, properties.getBatchSize());
        try {
            for (int from = 0; from < prepared.size(); from += batchSize) {
                int to = Math.min(from + batchSize, prepared.size());
                List<PreparedChunk> batch = prepared.subList(from, to);
                List<Document> documents = new ArrayList<>(batch.size());
                List<String> batchIds = new ArrayList<>(batch.size());
                for (PreparedChunk pc : batch) {
                    documents.add(toVectorDocument(pc));
                    batchIds.add(pc.chunkId());
                }
                // 框架原生 DB 级 Upsert：稳定 UUID 冲突即更新，不产生重复行
                vectorStore.add(documents);
                repository.updateChunkStatuses(documentId, targetVersion, batchIds,
                        ChunkRowStatus.INDEXED.name(), LocalDateTime.now());
                processed += batch.size();
                repository.updateTaskProgress(documentId, targetVersion,
                        VectorIndexStatus.INDEXING.name(), VectorIndexStage.EMBED_WRITE.name(),
                        "", "", attempt, null, LocalDateTime.now());
            }
        } catch (RuntimeException ex) {
            persistFailure(documentId, targetVersion, attempt, processed, ex);
            throw new VectorIndexException(ResultCode.VECTOR_INDEX_FAILED,
                    "向量写入失败，已记录可恢复状态: " + safeMessage(ex), ex,
                    ex instanceof VectorIndexException vectorEx
                            ? vectorEx.getErrorCode() : "EMBED_WRITE_FAILED");
        }

        // 同版本内容变更：先清理不再使用或超出新数量的旧 Chunk 向量与关联，
        // 再核对数量；顺序颠倒会让"旧行仍在"被误判为写入失败。
        Set<String> newIds = new LinkedHashSet<>();
        for (PreparedChunk pc : prepared) {
            newIds.add(pc.chunkId());
        }
        List<String> staleIds = previousChunkIds.stream()
                .filter(id -> !newIds.contains(id))
                .toList();
        deleteVectorsSafely(documentId, targetVersion, staleIds, "同版本旧向量");
        repository.deleteChunksFromIndex(documentId, targetVersion, normalized.size());

        // 写入后核对：PG 行数、MySQL 关联数与已索引行数都必须等于 Chunk 数
        long pgCount = pgVectorAccess.countByDocumentVersion(documentId, targetVersion);
        int relationCount = repository.countChunks(documentId, targetVersion);
        int indexedCount = repository.countChunksByStatus(documentId, targetVersion,
                ChunkRowStatus.INDEXED.name());
        if (pgCount != normalized.size() || relationCount != normalized.size()
                || indexedCount != normalized.size()) {
            String detail = "pg=%d relation=%d indexed=%d expected=%d"
                    .formatted(pgCount, relationCount, indexedCount, normalized.size());
            repository.updateTaskProgress(documentId, targetVersion,
                    VectorIndexStatus.PARTIAL.name(), VectorIndexStage.VERIFY.name(),
                    "COUNT_MISMATCH", safeMessage(detail), attempt,
                    now.plusNanos(properties.getRetryBackoffMs() * 1_000_000L),
                    LocalDateTime.now());
            throw new VectorIndexException(ResultCode.VECTOR_INDEX_FAILED,
                    "写入后核对不一致：" + detail, null, "COUNT_MISMATCH");
        }

        // 版本切换（MySQL 本地事务）：旧活动版本 SUPERSEDED，当前版本 COMPLETED
        List<VectorIndexTaskRow> olderCompleted = repository.findTasksByDocument(documentId)
                .stream()
                .filter(row -> !targetVersion.equals(row.getVersion()))
                .filter(row -> VectorIndexStatus.COMPLETED.name().equals(row.getStatus()))
                .toList();
        LocalDateTime switchTime = LocalDateTime.now();
        transactionTemplate.executeWithoutResult(status -> {
            for (VectorIndexTaskRow older : olderCompleted) {
                repository.updateTaskProgress(documentId, older.getVersion(),
                        VectorIndexStatus.SUPERSEDED.name(), older.getStage(),
                        "", "", older.getAttemptCount(), null, switchTime);
            }
            repository.updateTaskProgress(documentId, targetVersion,
                    VectorIndexStatus.COMPLETED.name(), VectorIndexStage.SWITCH.name(),
                    "", "", attempt, null, switchTime);
        });

        // 清理旧版本向量（失败不阻断新版本，留给核对补偿）
        cleanupSupersededVersions(documentId);

        repository.updateTaskProgress(documentId, targetVersion,
                VectorIndexStatus.COMPLETED.name(), VectorIndexStage.CLEANUP.name(),
                "", "", attempt, null, LocalDateTime.now());

        log.info("向量索引完成 documentId={} version={} chunks={} attempt={}",
                documentId, targetVersion, normalized.size(), attempt);
        VectorIndexTaskRow finalTask = repository.findTask(documentId, targetVersion)
                .orElse(task);
        return toResult(finalTask, normalized.size(), processed, false);
    }

    // ------------------------------------------------------------------
    // 删除（按文档 / 按版本）
    // ------------------------------------------------------------------

    /**
     * 删除指定文档版本的全部向量与关联。
     *
     * <p>顺序：先按稳定 chunkId 删 PG（对不存在 id 不报错，可重复执行），
     * 成功后再在 MySQL 本地事务内删关联与任务行。</p>
     */
    public void deleteByDocumentVersion(String documentId, String version) {
        requireDocumentId(documentId);
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        List<VectorChunkRow> rows = repository.findChunks(documentId, version.strip());
        deleteVectorsSafely(documentId, version.strip(),
                rows.stream().map(VectorChunkRow::getChunkId).toList(), "按版本删除");
        transactionTemplate.executeWithoutResult(status -> {
            repository.deleteChunks(documentId, version.strip());
            repository.deleteTask(documentId, version.strip());
        });
        log.info("文档版本向量已删除 documentId={} version={} chunks={}",
                documentId, version, rows.size());
    }

    /**
     * 删除指定文档全部版本的向量与关联；重复执行安全。
     */
    public void deleteByDocument(String documentId) {
        requireDocumentId(documentId);
        List<VectorChunkRow> rows = repository.findChunksByDocument(documentId);
        deleteVectorsSafely(documentId, null,
                rows.stream().map(VectorChunkRow::getChunkId).toList(), "按文档删除");
        transactionTemplate.executeWithoutResult(status -> {
            repository.deleteChunksByDocument(documentId);
            repository.deleteTasksByDocument(documentId);
        });
        log.info("文档全部向量已删除 documentId={} chunks={}", documentId, rows.size());
    }

    // ------------------------------------------------------------------
    // 恢复（卡住任务 / 可重试失败）
    // ------------------------------------------------------------------

    /**
     * 恢复卡住任务：扫描 PENDING/INDEXING 且超过 stuckMinutes 的行。
     *
     * <p>仅当 Outbox 快照完整（快照条数等于任务 chunkCount）时才能幂等重建；
     * 快照不完整说明在写入前即崩溃，无法凭空重建全量，标记 FAILED 等待重新切分。</p>
     */
    public List<VectorIndexResultView> recoverStuckTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        List<VectorIndexResultView> results = new ArrayList<>();
        for (VectorIndexTaskRow task : repository.findStuckTasks(cutoff)) {
            if (task.getAttemptCount() >= properties.getMaxAttempts()) {
                markManualRequired(task, "卡住任务已达最大尝试次数，需人工处理");
                continue;
            }
            VectorIndexResultView result = resumeFromSnapshot(task);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 重试可恢复失败任务（PARTIAL/FAILED 且未达最大尝试次数）。
     */
    public List<VectorIndexResultView> retryFailedTasks() {
        List<VectorIndexResultView> results = new ArrayList<>();
        for (VectorIndexTaskRow task : repository.findRetryableTasks(properties.getMaxAttempts())) {
            VectorIndexResultView result = resumeFromSnapshot(task);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private VectorIndexResultView resumeFromSnapshot(VectorIndexTaskRow task) {
        List<VectorChunkRow> snapshot =
                repository.findChunks(task.getDocumentId(), task.getVersion());
        if (snapshot.isEmpty() || snapshot.size() < task.getChunkCount()) {
            markManualRequired(task, "Outbox 快照不完整，需重新切分后重新索引");
            return null;
        }
        List<DocumentChunk> chunks = toDocumentChunks(snapshot, task.getStrategy());
        log.info("从 Outbox 快照恢复索引 documentId={} version={} chunks={}",
                task.getDocumentId(), task.getVersion(), chunks.size());
        return indexChunks(task.getDocumentId(), task.getVersion(),
                task.getStrategy(), null, chunks);
    }

    private void markManualRequired(VectorIndexTaskRow task, String message) {
        repository.updateTaskProgress(task.getDocumentId(), task.getVersion(),
                VectorIndexStatus.FAILED.name(), task.getStage(),
                "NO_COMPLETE_SNAPSHOT", safeMessage(message), task.getAttemptCount(),
                null, LocalDateTime.now());
        log.warn("任务无法自动恢复，需人工介入 documentId={} version={} reason={}",
                task.getDocumentId(), task.getVersion(), message);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<VectorIndexTaskView> getStatus(String documentId) {
        requireDocumentId(documentId);
        return repository.findTasksByDocument(documentId).stream()
                .map(this::toTaskView)
                .toList();
    }

    /**
     * 查询文档的 Chunk 关联；version 为空时返回全部版本。
     */
    public List<ChunkIndexView> listChunks(String documentId, String version) {
        requireDocumentId(documentId);
        List<VectorChunkRow> rows = version == null || version.isBlank()
                ? repository.findChunksByDocument(documentId)
                : repository.findChunks(documentId, version.strip());
        return rows.stream().map(this::toChunkView).toList();
    }

    // ------------------------------------------------------------------
    // 内部：载荷准备
    // ------------------------------------------------------------------

    private boolean isIdempotentHit(VectorIndexTaskRow task, String payloadHash, int chunkCount) {
        if (!VectorIndexStatus.COMPLETED.name().equals(task.getStatus())) {
            return false;
        }
        if (!payloadHash.equals(task.getContentHash())) {
            return false;
        }
        if (task.getChunkCount() != chunkCount) {
            return false;
        }
        if (!properties.getSplitVersion().equals(task.getSplitVersion())) {
            return false;
        }
        if (!descriptor.vectorSpaceId().equals(task.getVectorSpaceId())) {
            return false;
        }
        if (repository.countChunks(task.getDocumentId(), task.getVersion()) != chunkCount) {
            return false;
        }
        if (repository.countChunksByStatus(task.getDocumentId(), task.getVersion(),
                ChunkRowStatus.INDEXED.name()) != chunkCount) {
            return false;
        }
        return pgVectorAccess.countByDocumentVersion(task.getDocumentId(), task.getVersion())
                == chunkCount;
    }

    private List<PreparedChunk> prepare(List<DocumentChunk> chunks) {
        List<PreparedChunk> prepared = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            String chunkHash = hasher.hash(chunk.content());
            String chunkId = idGenerator.generate(chunk.documentId(), chunk.version(),
                    chunk.strategy() == null ? "" : chunk.strategy().name(), chunk.chunkIndex(),
                    chunkHash, properties.getSplitVersion(), descriptor.vectorSpaceId());
            prepared.add(new PreparedChunk(chunk, chunkHash, chunkId));
        }
        return prepared;
    }

    private List<DocumentChunk> normalize(String documentId, String version,
                                          ChunkStrategy strategy, String title,
                                          List<DocumentChunk> source) {
        List<DocumentChunk> result = new ArrayList<>(source.size());
        for (DocumentChunk original : source) {
            if (original == null || original.content() == null
                    || original.content().isBlank()) {
                throw new IllegalArgumentException("存在空白 Chunk，禁止写入无意义向量");
            }
            String content = original.content();
            result.add(new DocumentChunk(
                    documentId,
                    title != null && !title.isBlank() ? title : original.title(),
                    version,
                    original.chunkIndex(),
                    strategy,
                    content,
                    content.codePointCount(0, content.length()),
                    original.pageNumbers(),
                    original.sources(),
                    original.endBoundary()));
        }
        result.sort(Comparator.comparingInt(DocumentChunk::chunkIndex));
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).chunkIndex() != i) {
                throw new IllegalArgumentException(
                        "Chunk 顺序必须为 0..n-1 连续，当前第 " + i + " 块索引为 "
                                + result.get(i).chunkIndex());
            }
        }
        return result;
    }

    private Document toVectorDocument(PreparedChunk pc) {
        DocumentChunk chunk = pc.chunk();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.documentId());
        if (chunk.title() != null) {
            metadata.put("title", chunk.title());
        }
        metadata.put("version", chunk.version());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("strategy", chunk.strategy() == null ? null : chunk.strategy().name());
        metadata.put("pageNumbers", chunk.pageNumbers());
        metadata.put("characterCount", chunk.characterCount());
        metadata.put("vectorSpaceId", descriptor.vectorSpaceId());
        metadata.put("modelName", descriptor.modelName());
        metadata.put("chunkContentHash", pc.chunkContentHash());
        metadata.put("splitVersion", properties.getSplitVersion());
        return Document.builder()
                .id(pc.chunkId())
                .text(chunk.content())
                .metadata(metadata)
                .build();
    }

    private VectorChunkRow toChunkRow(PreparedChunk pc, LocalDateTime time, String status) {
        DocumentChunk chunk = pc.chunk();
        VectorChunkRow row = new VectorChunkRow();
        row.setDocumentId(chunk.documentId());
        row.setVersion(chunk.version());
        row.setStrategy(chunk.strategy().name());
        row.setChunkId(pc.chunkId());
        row.setChunkIndex(chunk.chunkIndex());
        row.setContent(chunk.content());
        row.setChunkContentHash(pc.chunkContentHash());
        row.setCharacterCount(chunk.characterCount());
        row.setPageNumbers(joinPageNumbers(chunk.pageNumbers()));
        row.setStatus(status);
        row.setCreatedAt(time);
        row.setUpdatedAt(time);
        return row;
    }

    private List<DocumentChunk> toDocumentChunks(List<VectorChunkRow> rows, String strategy) {
        ChunkStrategy chunkStrategy = ChunkStrategy.valueOf(strategy);
        List<DocumentChunk> chunks = new ArrayList<>(rows.size());
        for (VectorChunkRow row : rows) {
            chunks.add(new DocumentChunk(
                    row.getDocumentId(),
                    null,
                    row.getVersion(),
                    row.getChunkIndex(),
                    chunkStrategy,
                    row.getContent(),
                    row.getCharacterCount(),
                    splitPageNumbers(row.getPageNumbers()),
                    List.<ChunkSource>of(),
                    // 快照未保存边界类型；仅用于载荷重建，不参与稳定 ID
                    ChunkBoundaryType.HARD_LIMIT));
        }
        return chunks;
    }

    private String payloadText(List<DocumentChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            builder.append(chunk.chunkIndex()).append(':').append(chunk.content()).append('\n');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 内部：失败与清理
    // ------------------------------------------------------------------

    private void persistFailure(String documentId, String version, int attempt,
                                int processed, RuntimeException ex) {
        String errorCode = ex instanceof VectorIndexException vectorEx
                ? vectorEx.getErrorCode() : "EMBED_WRITE_FAILED";
        String status = processed > 0
                ? VectorIndexStatus.PARTIAL.name()
                : VectorIndexStatus.FAILED.name();
        try {
            repository.updateTaskProgress(documentId, version, status,
                    VectorIndexStage.EMBED_WRITE.name(), errorCode, safeMessage(ex), attempt,
                    LocalDateTime.now().plusNanos(
                            properties.getRetryBackoffMs() * 1_000_000L),
                    LocalDateTime.now());
        } catch (RuntimeException persistEx) {
            log.error("失败状态落账失败 documentId={} version={} 异常类型={}",
                    documentId, version, persistEx.getClass().getSimpleName());
        }
        log.warn("向量写入中断 documentId={} version={} processed={} reason={}",
                documentId, version, processed, safeMessage(ex));
    }

    private void deleteVectorsSafely(String documentId, String version,
                                     List<String> ids, String scene) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        int batchSize = Math.max(1, properties.getBatchSize());
        try {
            for (int from = 0; from < distinct.size(); from += batchSize) {
                int to = Math.min(from + batchSize, distinct.size());
                vectorStore.delete(distinct.subList(from, to));
            }
            log.info("{}向量删除完成 documentId={} version={} count={}",
                    scene, documentId, version, distinct.size());
        } catch (RuntimeException ex) {
            log.warn("{}向量删除失败 documentId={} version={} reason={}",
                    scene, documentId, version, safeMessage(ex));
            throw ex;
        }
    }

    private void cleanupSupersededVersions(String documentId) {
        for (VectorIndexTaskRow task : repository.findTasksByDocument(documentId)) {
            if (!VectorIndexStatus.SUPERSEDED.name().equals(task.getStatus())) {
                continue;
            }
            try {
                List<VectorChunkRow> rows =
                        repository.findChunks(documentId, task.getVersion());
                if (!rows.isEmpty()) {
                    deleteVectorsSafely(documentId, task.getVersion(),
                            rows.stream().map(VectorChunkRow::getChunkId).toList(), "旧版本清理");
                    repository.deleteChunks(documentId, task.getVersion());
                }
                log.info("旧版本向量已清理 documentId={} version={} chunks={}",
                        documentId, task.getVersion(), rows.size());
            } catch (RuntimeException ex) {
                // 非致命：新版本已活动，残留由核对补偿收敛
                log.warn("旧版本清理失败，等待核对补偿 documentId={} version={} reason={}",
                        documentId, task.getVersion(), safeMessage(ex));
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部：视图转换
    // ------------------------------------------------------------------

    private VectorIndexResultView toResult(VectorIndexTaskRow task, int chunkCount,
                                           int processed, boolean idempotent) {
        VectorIndexResultView view = new VectorIndexResultView();
        view.setDocumentId(task.getDocumentId());
        view.setVersion(task.getVersion());
        view.setStrategy(task.getStrategy());
        view.setStatus(task.getStatus());
        view.setStage(task.getStage());
        view.setChunkCount(chunkCount);
        view.setProcessed(processed);
        view.setIdempotent(idempotent);
        view.setAttemptCount(task.getAttemptCount());
        view.setErrorCode(task.getErrorCode());
        view.setErrorMessage(task.getErrorMessage());
        return view;
    }

    private VectorIndexTaskView toTaskView(VectorIndexTaskRow row) {
        VectorIndexTaskView view = new VectorIndexTaskView();
        view.setDocumentId(row.getDocumentId());
        view.setVersion(row.getVersion());
        view.setStrategy(row.getStrategy());
        view.setContentHash(row.getContentHash());
        view.setSplitVersion(row.getSplitVersion());
        view.setVectorSpaceId(row.getVectorSpaceId());
        view.setChunkCount(row.getChunkCount());
        view.setStatus(row.getStatus());
        view.setStage(row.getStage());
        view.setErrorCode(row.getErrorCode());
        view.setErrorMessage(row.getErrorMessage());
        view.setAttemptCount(row.getAttemptCount());
        view.setNextRetryAt(row.getNextRetryAt());
        view.setCreatedAt(row.getCreatedAt());
        view.setUpdatedAt(row.getUpdatedAt());
        return view;
    }

    private ChunkIndexView toChunkView(VectorChunkRow row) {
        ChunkIndexView view = new ChunkIndexView();
        view.setChunkId(row.getChunkId());
        view.setDocumentId(row.getDocumentId());
        view.setVersion(row.getVersion());
        view.setStrategy(row.getStrategy());
        view.setChunkIndex(row.getChunkIndex());
        view.setCharacterCount(row.getCharacterCount());
        view.setChunkContentHash(row.getChunkContentHash());
        view.setPageNumbers(row.getPageNumbers());
        view.setStatus(row.getStatus());
        view.setContent(row.getContent());
        return view;
    }

    // ------------------------------------------------------------------
    // 内部：基础工具
    // ------------------------------------------------------------------

    private void requireDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
    }

    private ChunkStrategy resolveStrategy(String strategy) {
        String value = strategy == null || strategy.isBlank()
                ? properties.getDefaultStrategy() : strategy.strip();
        try {
            return ChunkStrategy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "不支持的切分策略: " + value + "，可选 FIXED_LENGTH / STRUCTURE_AWARE");
        }
    }

    private String joinPageNumbers(List<Integer> pageNumbers) {
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Integer page : pageNumbers) {
            if (page == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(page);
        }
        return builder.toString();
    }

    private List<Integer> splitPageNumbers(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Integer> pages = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                pages.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 历史脏值忽略，不阻断恢复
            }
        }
        return pages;
    }

    private String safeMessage(RuntimeException ex) {
        return safeMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    private String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > MAX_ERROR_MESSAGE
                ? message.substring(0, MAX_ERROR_MESSAGE)
                : message;
    }

    /**
     * 预处理后的 Chunk 三元组。
     */
    private record PreparedChunk(DocumentChunk chunk, String chunkContentHash, String chunkId) {
    }
}
