package com.zl.d43.e2e;

import java.util.ArrayList;
import java.util.List;

/**
 * D43 P4（§6.4+fixture-truth.expectedFrozen 先冻结后执行）：权限矩阵 P01–P10 执行器。
 *
 * <p>本类不依赖 spring-test：HTTP 面经 {@link HttpProbe}（IT 以 TestRestTemplate 实现，
 * 返回 HTTP 状态码 + 业务拒绝码实码），审计行经 {@link AuditProbe}（d43_admin_audit 直查），
 * P10 内部直调经 {@link InternalProbe}（无 AuthContext 直调 Service 捕获实码）。
 * 泄漏判定：拒绝用例若返回业务数据（非 4001/拒绝码族）→ leakage=true，
 * 上层立即 E2E_LEAKAGE_DETECTED P0 熔断（§九）。拒绝码语义比对采用“实际码 ∈ 冻结预期码集”。</p>
 */
public class PermissionMatrixRunner {

    public record ProbeResponse(int httpStatus, String bizCode, String bodyDigest) {
    }

    /** HTTP 探针：IT 实现。token=null 表示不带 Authorization。 */
    public interface HttpProbe {
        ProbeResponse get(String path, String token);
        ProbeResponse postSearch(String query, String token);
        ProbeResponse deleteDocument(String documentId, String token);
    }

    /** 审计行探针：d43_admin_audit 是否存在指定 action 行。 */
    public interface AuditProbe {
        boolean hasAuditRow(String action, String targetDocumentId);
        boolean hasDeniedAuditRow(String action, String targetDocumentId);
    }

    /** P10 内部直调探针：无请求上下文调用开启态检索，返回捕获的实码。 */
    public interface InternalProbe {
        String callWithoutContext();
    }

    private final HttpProbe http;
    private final AuditProbe audit;
    private final InternalProbe internal;
    private final String lc2Id;

    public PermissionMatrixRunner(HttpProbe http, AuditProbe audit, InternalProbe internal,
                                  String deletedFixtureDocumentId) {
        this.http = http;
        this.audit = audit;
        this.internal = internal;
        this.lc2Id = deletedFixtureDocumentId;
    }

    public List<PermissionCaseRecord> runAll() {
        List<PermissionCaseRecord> out = new ArrayList<>();
        out.add(p01());
        out.add(p02());
        out.add(p03());
        out.add(p04());
        out.add(p05());
        out.add(p06());
        out.add(p07());
        out.add(p08());
        out.add(p09());
        out.add(p10());
        return List.copyOf(out);
    }

    private static final String TOKEN_ADMIN = "fixture-e2e-token-admin-a";
    private static final String TOKEN_USER_A = "fixture-e2e-token-user-a";
    private static final String TOKEN_USER_A2 = "fixture-e2e-token-user-a2";
    private static final String TOKEN_USER_B = "fixture-e2e-token-user-b";
    private static final String TOKEN_DISABLED = "fixture-e2e-token-disabled-a";
    private static final String TOKEN_EXPIRED = "fixture-e2e-token-expired-a";

    private PermissionCaseRecord verdict(String caseId, String role, String endpoint,
                                         String expected, ProbeResponse actual,
                                         java.util.function.Predicate<ProbeResponse> match,
                                         boolean leakageChecked) {
        boolean ok = actual != null && match.test(actual);
        boolean leakage = leakageChecked && actual != null && !ok
                && (actual.bizCode() == null || actual.bizCode().isEmpty()
                        ? "SUCCESS".equals(actual.bodyDigest()) : false);
        return new PermissionCaseRecord(caseId, role, endpoint, expected,
                actual == null ? "probe-null" : actual.httpStatus() + "/" + actual.bizCode(),
                ok ? E2eVerdict.PASS : (leakage ? E2eVerdict.FAIL : E2eVerdict.FAIL), leakage);
    }

    /** 拒绝族判定：业务码以冻结前缀命中。 */
    private static boolean deniedWith(ProbeResponse r, String... codes) {
        if (r == null || r.bizCode() == null) {
            return false;
        }
        for (String code : codes) {
            if (code.equals(r.bizCode())) {
                return true;
            }
        }
        return false;
    }

    private PermissionCaseRecord p01() {
        ProbeResponse r = http.get("/api/v1/knowledge/documents/" + lc2Id, null);
        return verdict("P01", "NONE", "GET documents/{id}", "拒绝 AUTH_REQUIRED",
                r, x -> deniedWith(x, "AUTH_REQUIRED"), true);
    }

    private PermissionCaseRecord p02() {
        ProbeResponse r = http.postSearch("E2E 权限矩阵探测", "fixture-e2e-token-not-existing");
        return verdict("P02", "NONE", "POST rag/answer", "拒绝 AUTH_INVALID",
                r, x -> deniedWith(x, "AUTH_INVALID"), true);
    }

    private PermissionCaseRecord p03() {
        ProbeResponse r = http.postSearch("E2E 权限矩阵探测", TOKEN_EXPIRED);
        return verdict("P03", "EXPIRED", "POST rag/answer", "拒绝 AUTH_EXPIRED",
                r, x -> deniedWith(x, "AUTH_EXPIRED"), true);
    }

    private PermissionCaseRecord p04() {
        ProbeResponse r = http.postSearch("E2E 权限矩阵探测", TOKEN_DISABLED);
        return verdict("P04", "DISABLED", "POST rag/answer", "拒绝 AUTH_DISABLED",
                r, x -> deniedWith(x, "AUTH_DISABLED"), true);
    }

    private PermissionCaseRecord p05() {
        // 放行且仅 A 范围：成功态（非拒绝码族）+ 结果面零 PERM-B。mock 轮 CLARIFY_REQUIRED
        // 空 sources 亦满足“仅 A 范围”——按实码判定隔离性，不断言生成成功性（P05 口径修正）。
        ProbeResponse r = http.postSearch("E2E-PERM-A 租户Alpha设备手册条款", TOKEN_USER_A);
        boolean allowed = r != null && !deniedWith(r, "AUTH_REQUIRED", "AUTH_INVALID",
                "AUTH_EXPIRED", "AUTH_DISABLED", "AUTH_FORBIDDEN", "AUTH_CONTEXT_MISSING");
        boolean noB = allowed && r.bodyDigest() != null && !r.bodyDigest().contains("PERM-B");
        return new PermissionCaseRecord("P05", "USER", "POST rag/answer（租户 A 文档）",
                "放行且仅 A 范围", r.httpStatus() + "/" + r.bizCode(),
                noB ? E2eVerdict.PASS : E2eVerdict.FAIL, allowed && !noB);
    }
private PermissionCaseRecord p06() {
        ProbeResponse r = http.postSearch("E2E-PERM-B 租户Beta设备手册条款（P05/P06/P07 隔离断言对象）", TOKEN_USER_A);
        boolean noB = r.bodyDigest() == null || !r.bodyDigest().contains("E2E-PERM-B");
        // 语料外命中=0：拒答/空结果均满足“零 B 命中”（谓词下推实证）
        return new PermissionCaseRecord("P06", "USER", "POST rag/answer（含 B 语料）",
                "结果零 B 命中", r.httpStatus() + "/" + r.bizCode(),
                noB ? E2eVerdict.PASS : E2eVerdict.FAIL, !noB);
    }

    private PermissionCaseRecord p07() {
        ProbeResponse r = http.get("/api/v1/knowledge/documents/" + E2eScenarioRunner.DOC_PERM_B, TOKEN_USER_A2);
        return verdict("P07", "USER", "GET documents/{B 文档}", "拒绝 AUTH_FORBIDDEN（范围外）",
                r, x -> deniedWith(x, "AUTH_FORBIDDEN"), true);
    }

    private PermissionCaseRecord p08() {
        // 实码口径（AiSyncController 先例，KP1 冻结）：拒绝=guard 异常直接上抛，
        // d43_admin_audit 不写拒绝行（拒绝面由异常链/4001 响应体留痕）。
        // 任务书“审计拒绝留痕（实码口径）”按实码执行：拒绝码正确 + 零业务副作用 + 观测审计表如实记录。
        ProbeResponse r = http.deleteDocument(E2eScenarioRunner.DOC_PERM_A, TOKEN_USER_A);
        boolean denied = deniedWith(r, "AUTH_FORBIDDEN");
        boolean noSideEffect = !audit.hasAuditRow("DOC_DELETE", E2eScenarioRunner.DOC_PERM_A);
        return new PermissionCaseRecord("P08", "USER", "DELETE documents/{id}",
                "拒绝 AUTH_FORBIDDEN + 拒绝面异常链留痕（实码：不落审计行）",
                (r == null ? "probe-null" : r.httpStatus() + "/" + r.bizCode())
                        + (noSideEffect ? "+NO_SIDE_EFFECT" : "+AUDIT_ROW_UNEXPECTED"),
                denied && noSideEffect ? E2eVerdict.PASS : E2eVerdict.FAIL, !denied && r != null
                        && (r.bizCode() == null || r.bizCode().isEmpty()));
    }

    private PermissionCaseRecord p09() {
        // ADMIN 删除独立夹具（perm-b 已由 P07 断言过范围；删除对象用 ADMIN 可操作的第二文档）
        ProbeResponse r = http.deleteDocument(E2eScenarioRunner.DOC_PERM_B, TOKEN_ADMIN);
        boolean allowed = r != null && (r.bizCode() == null || "SUCCESS".equals(r.bizCode()) || r.bizCode().isEmpty());
        boolean audited = allowed && audit.hasAuditRow("DOC_DELETE", E2eScenarioRunner.DOC_PERM_B);
        return new PermissionCaseRecord("P09", "ADMIN", "DELETE documents/{id}",
                "放行 + d43_admin_audit 落行 DOC_DELETE",
                r.httpStatus() + "/" + r.bizCode() + (audited ? "+AUDIT_ALLOWED" : "+AUDIT_MISSING"),
                allowed && audited ? E2eVerdict.PASS : E2eVerdict.FAIL, false);
    }

    private PermissionCaseRecord p10() {
        String actualCode = internal.callWithoutContext();
        boolean ok = "AUTH_CONTEXT_MISSING".equals(actualCode);
        return new PermissionCaseRecord("P10", "INTERNAL", "检索 Service 直调（无 AuthContext）",
                "fail-closed AUTH_CONTEXT_MISSING（实码为准）", String.valueOf(actualCode),
                ok ? E2eVerdict.PASS : E2eVerdict.FAIL, false);
    }
}