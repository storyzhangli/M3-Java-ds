package com.zl.d42.knowledge.sync;

import com.zl.d42.access.AuthContext;
import com.zl.d42.knowledge.sync.AdminAuditRepository;
import com.zl.d42.knowledge.sync.AdminAuditRow;
import com.zl.d42.logging.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 特权操作审计服务（R01；仅随 access.enabled 装配——disabled 时 Bean 缺席，
 * 控制器经 ObjectProvider 取到 null 即不写，零差异，H2/C05）。
 *
 * <p>写入时机（KP1 裁决）：guard.requireAdmin() 通过后执行前记 ALLOWED；
 * 业务失败时补记 result=FAILED+reason_code（同 action 两行语义清晰，避免改写）。
 * 拒绝（DENIED）由既有 4001 异常链与过滤器日志覆盖，不在本面双写。</p>
 *
 * <p>审计写失败绝不阻断主操作（安全审计非业务事务，best-effort + error 日志留痕）。</p>
 */
@Slf4j
public class AuditService {

    private final AdminAuditRepository auditRepository;

    public AuditService(AdminAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void recordAllowed(AuthContext actor, String action,
                              String targetDocumentId, String version) {
        record(actor, action, targetDocumentId, version, "ALLOWED", "");
    }

    public void recordFailed(AuthContext actor, String action, String targetDocumentId,
                             String version, String reasonCode) {
        record(actor, action, targetDocumentId, version, "FAILED", reasonCode);
    }

    private void record(AuthContext actor, String action, String targetDocumentId,
                        String version, String result, String reasonCode) {
        try {
            AdminAuditRow row = new AdminAuditRow();
            row.setActorUserId(actor == null ? "" : actor.userId());
            row.setTenantId(actor == null ? "" : actor.tenantId());
            row.setRole(actor == null ? "" : actor.role());
            row.setAction(action);
            row.setTargetDocumentId(targetDocumentId == null ? "" : targetDocumentId);
            row.setVersion(version == null ? "" : version);
            row.setResult(result);
            row.setReasonCode(reasonCode == null ? "" : reasonCode);
            String traceId = TraceIdFilter.currentTraceId();
            row.setRequestId(traceId == null || traceId.isBlank()
                    ? "gen-" + UUID.randomUUID() : traceId);
            row.setCreatedAt(LocalDateTime.now());
            auditRepository.insert(row);
        } catch (DataAccessException ex) {
            log.error("审计落库失败（不阻断主操作）action={} target={} reason={}",
                    action, targetDocumentId, ex.getMessage());
        }
    }
}
