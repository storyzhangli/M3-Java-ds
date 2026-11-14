package com.zl.d31.knowledge.vectorindex.controller;

import com.zl.d31.common.Result;
import com.zl.d31.knowledge.vectorindex.dto.ChunkIndexView;
import com.zl.d31.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexTaskView;
import com.zl.d31.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d31.knowledge.vectorindex.service.VectorReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 向量索引运维入口：建索引、查状态、查 Chunk、删除、恢复与核对。
 *
 * <<p>设计为运维/验收接口而非高频业务读接口；所有写操作幂等，可重复调用。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class VectorIndexController {

    private final DocumentVectorIndexService indexService;

    private final VectorReconciliationService reconciliationService;

    public VectorIndexController(DocumentVectorIndexService indexService,
                                 VectorReconciliationService reconciliationService) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * 从知识文档页面重新切分并幂等入库；重复调用同一文档命中幂等短路。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<VectorIndexResultView> index(
            @PathVariable String documentId,
            @RequestParam(required = false) String strategy) {
        return Result.success(indexService.indexFromPages(documentId, strategy));
    }

    /**
     * 查询文档全部版本的索引任务状态。
     */
    @GetMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<List<VectorIndexTaskView>> status(@PathVariable String documentId) {
        return Result.success(indexService.getStatus(documentId));
    }

    /**
     * 查询 Chunk 关联；version 省略时返回全部版本。
     */
    @GetMapping("/api/v1/knowledge/documents/{documentId}/vector-index/chunks")
    public Result<List<ChunkIndexView>> chunks(
            @PathVariable String documentId,
            @RequestParam(required = false) String version) {
        return Result.success(indexService.listChunks(documentId, version));
    }

    /**
     * 删除向量索引；version 省略时删除该文档全部版本。
     */
    @DeleteMapping("/api/v1/knowledge/documents/{documentId}/vector-index")
    public Result<Void> delete(
            @PathVariable String documentId,
            @RequestParam(required = false) String version) {
        if (version == null || version.isBlank()) {
            indexService.deleteByDocument(documentId);
        } else {
            indexService.deleteByDocumentVersion(documentId, version);
        }
        return Result.success(null);
    }

    /**
     * 手动触发卡住任务恢复（基于 Outbox 快照幂等重建）。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index/recover")
    public Result<List<VectorIndexResultView>> recover(@PathVariable String documentId) {
        log.info("手动触发卡住任务恢复 documentId={}", documentId);
        return Result.success(indexService.recoverStuckTasks());
    }

    /**
     * 手动触发失败任务重试。
     */
    @PostMapping("/api/v1/knowledge/documents/{documentId}/vector-index/retry")
    public Result<List<VectorIndexResultView>> retry(@PathVariable String documentId) {
        log.info("手动触发失败任务重试 documentId={}", documentId);
        return Result.success(indexService.retryFailedTasks());
    }

    /**
     * 跨系统一致性核对；dryRun=true 只出报告。
     */
    @PostMapping("/api/v1/knowledge/vector-index/reconcile")
    public Result<ReconciliationReportView> reconcile(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return Result.success(reconciliationService.reconcile(dryRun));
    }
}
