package com.zl.d40.knowledge.sync;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * d40_admin_audit 行投影（特权操作审计留痕，随 access.enabled 门禁装配）。
 */
@Data
public class AdminAuditRow {

    private long id;

    private String actorUserId;

    private String tenantId;

    private String role;

    /** 操作标识，如 VECTOR_INDEX / VECTOR_DELETE / SYNC_RETRY / CONSISTENCY_CHECK。 */
    private String action;

    private String targetDocumentId;

    private String version;

    /** ALLOWED（guard 校验通过后执行）。拒绝路径由 4001 异常链+过滤器日志覆盖（KP1 裁决）。 */
    private String result;

    /** 失败原因码；ALLOWED 行为空串。 */
    private String reasonCode;

    /** 请求追踪 ID（MDC traceId 优先，缺失时 UUID）。 */
    private String requestId;

    private LocalDateTime createdAt;
}
