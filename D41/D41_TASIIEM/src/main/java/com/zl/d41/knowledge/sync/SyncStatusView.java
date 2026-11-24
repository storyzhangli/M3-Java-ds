package com.zl.d41.knowledge.sync;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步状态视图（GET /api/v1/knowledge/sync/status 返回投影，字段显式声明，
 * 不使用无约束 Map，核心契约纪律）。
 */
@Data
public class SyncStatusView {

    private String documentId;

    private String operation;

    private String version;

    private String syncStatus;

    private String reasonCode;

    private int attemptCount;

    private LocalDateTime nextRetryAt;

    private String payloadHash;

    private LocalDateTime updatedAt;

    public static SyncStatusView of(DocumentSyncRow row) {
        SyncStatusView view = new SyncStatusView();
        view.setDocumentId(row.getDocumentId());
        view.setOperation(row.getOperation());
        view.setVersion(row.getVersion());
        view.setSyncStatus(row.getSyncStatus());
        view.setReasonCode(row.getReasonCode());
        view.setAttemptCount(row.getAttemptCount());
        view.setNextRetryAt(row.getNextRetryAt());
        view.setPayloadHash(row.getPayloadHash());
        view.setUpdatedAt(row.getUpdatedAt());
        return view;
    }
}
