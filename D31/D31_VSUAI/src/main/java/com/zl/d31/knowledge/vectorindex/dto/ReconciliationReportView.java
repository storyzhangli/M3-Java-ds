package com.zl.d31.knowledge.vectorindex.dto;

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

    private final List<String> actionsTaken = new ArrayList<>();

    public int totalIssues() {
        return orphanVectors.size() + missingVectors.size()
                + countMismatches.size() + stuckTasks.size();
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
