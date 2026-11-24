package com.zl.d41.knowledge.vectorindex.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨系统一致性核对报告（MySQL 关联/状态 vs PostgreSQL 向量表）。
 */
@Data
public class ReconciliationReportView {

    private boolean dryRun;

    private long pgTotalRows;

    private int relationRows;

    private int taskRows;

    private final List<Issue> orphanVectors = new ArrayList<>();

    private final List<Issue> missingVectors = new ArrayList<>();

    private final List<Issue> countMismatches = new ArrayList<>();

    private final List<Issue> stuckTasks = new ArrayList<>();

    /**
     * R01：版本指针漂移（权威 active 指针 vs knowledge_document.version / 关联行 / PG metadata，
     * 分类 DRIFT_VERSION_MISMATCH；SUPERSEDED 历史行不计入，STALE_VERSION 在 orphanVectors
     * 明细 detail 含 status=SUPERSEDED 区分）。
     */
    private final List<Issue> versionMismatches = new ArrayList<>();

    /**
     * R01：d41_document_sync 卡死（PENDING 超 next_retry_at 宽限未收敛，分类 DRIFT_SYNC_STUCK；
     * 修复通道=同步重试端点管线，禁止直改 SQL）。
     */
    private final List<Issue> syncStuck = new ArrayList<>();

    /**
     * D41 追加式：d41_table_extract/d41_table_row 孤儿记录
     * （文档不存在或版本与权威 active 指针不符；检出分类 TABLE_ORPHAN，
     * 修复通道=删除闭环/重抽取端点管线，禁止直改 SQL；table.enabled=false 时恒空，H4）。
     */
    private final List<Issue> tableOrphans = new ArrayList<>();

    private final List<String> actionsTaken = new ArrayList<>();

    public int totalIssues() {
        return orphanVectors.size() + missingVectors.size()
                + countMismatches.size() + stuckTasks.size()
                + versionMismatches.size() + syncStuck.size()
                + tableOrphans.size();
    }

    /**
     * 单条不一致明细。
     */
    @Data
    @AllArgsConstructor
    public static class Issue {

        private String key;

        private String documentId;

        private String version;

        private String detail;
    }
}
