package com.zl.d42.access;

import com.zl.d42.access.AuthContext;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限谓词唯一构建器（三轨同口径，§3.1）。
 *
 * <p>谓词代数（USER）：{@code tenantId==ctx.tenant AND (deptId==ctx.dept OR deptId==ALL)
 * AND (deviceId IN ctx.devices OR deviceId==ALL)}；ADMIN：{@code tenantId==ctx.tenant}
 * （租户硬边界 H7，ADMIN 仅租户内 bypass 部门/设备）。缺失权限键的行对 eq/in 天然不成立
 * （json 列 NULL 语义，KP3 实证）——fail-closed，无需显式 IS NOT NULL。</p>
 *
 * <p>哨兵 {@code ALL}（KP3 冻结）落在既有白名单字符集内；哨兵必须显式写入文档标签，
 * 不得用 null 表达“不限”（null=无标签=权限空间外，D3）。</p>
 *
 * <p>白名单纪律：权限值来自服务端 AuthContext/凭据表，不经请求值 requireSafe 通道，
 * 但此处仍做同字符集校验——防凭据表被污染后拼出异常表达式（§3.1）。</p>
 */
public class AccessPolicyService {

    /** 「不限」哨兵（KP3 冻结值，access-params.json）。 */
    public static final String SENTINEL = "ALL";

    private static final int MAX_VALUE_LENGTH = 128;
    private static final String SAFE_VALUE_PATTERN = "[A-Za-z0-9._:-]+";

    /**
     * 值对象化的权限范围：关键词路（内存判定）与向量路（表达式下推）共同消费，
     * 保证三轨代数等价、禁止两处各写一套逻辑漂移。
     */
    public record PermissionScope(String tenantId, Set<String> deptIds,
                                  Set<String> deviceIds, boolean admin) {
        public PermissionScope {
            deptIds = Set.copyOf(deptIds);
            deviceIds = Set.copyOf(deviceIds);
        }

        /**
         * 行级判定（唯一权威代数，向量路表达式与本判定单测断言等价，§3.1）：
         * 缺失键=null 一律拒绝（fail-closed）；ADMIN 仅租户边界。
         */
        public boolean allows(String rowTenant, String rowDept, String rowDevice) {
            if (rowTenant == null || !tenantId.equals(rowTenant)) {
                return false;
            }
            if (admin) {
                return true;
            }
            if (rowDept == null || !deptIds.contains(rowDept)) {
                return false;
            }
            return rowDevice != null && deviceIds.contains(rowDevice);
        }
    }

    public PermissionScope scopeFor(AuthContext context) {
        if (context == null) {
            throw new IllegalStateException("scopeFor 不接受 null AuthContext（fail-closed 由调用方保证）");
        }
        String tenant = requireSafeValue(context.tenantId(), "tenantId");
        if (context.isAdmin()) {
            return new PermissionScope(tenant, Set.of(), Set.of(), true);
        }
        String dept = requireSafeValue(context.deptId(), "deptId");
        Set<String> depts = new LinkedHashSet<>();
        depts.add(dept);
        depts.add(SENTINEL);
        Set<String> devices = new LinkedHashSet<>();
        for (String device : context.deviceScopes()) {
            devices.add(requireSafeValue(device, "deviceId"));
        }
        devices.add(SENTINEL);
        return new PermissionScope(tenant, Set.copyOf(depts), Set.copyOf(devices), false);
    }

    /** 向量路：构建可下推的权限表达式（本地与 two_ 共享实现）。 */
    public Filter.Expression vectorFilter(AuthContext context) {
        FilterExpressionBuilder.Op op = vectorFilterOp(context);
        return op.build();
    }

    /**
     * Op 级权限表达式——供调用方与业务过滤在 Op 层 AND 组合后再 build
     * （{@code Filter.Expression} 终态不可二次组合）。谓词代数与文档 §3.1 一致。
     */
    public FilterExpressionBuilder.Op vectorFilterOp(AuthContext context) {
        PermissionScope scope = scopeFor(context);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op tenantEq = b.eq("tenantId", scope.tenantId());
        if (scope.admin()) {
            return tenantEq;
        }
        // USER 主体 deptId 应为具体部门；哨兵化配置（dept=ALL）退化为"部门不限"，防御处理不抛死
        String realDept = scope.deptIds().stream()
                .filter(id -> !SENTINEL.equals(id)).findFirst().orElse(SENTINEL);
        // 安全修复（探针 D42PgFilterProbe 实证）：Spring AI 1.1.8
        // PgVectorFilterExpressionConverter 渲染 jsonpath 时仅对 Group 节点加括号；
        // 裸 OR 表达式会被展平进 && 链导致优先级丢失
        // （A && B || C && D || E ⇒ (A&&B) || (C&&D) || E，设备哨兵段恒真，跨部门/跨租户泄漏）。
        // 所有 OR 子树必须以 b.group(...) 包裹后才能安全下推。
        FilterExpressionBuilder.Op deptPart = SENTINEL.equals(realDept)
                ? b.eq("deptId", SENTINEL)
                : b.group(b.or(b.eq("deptId", realDept), b.eq("deptId", SENTINEL)));
        List<String> realDevices = scope.deviceIds().stream()
                .filter(id -> !SENTINEL.equals(id)).sorted().toList();
        FilterExpressionBuilder.Op devicePart = realDevices.isEmpty()
                ? b.eq("deviceId", SENTINEL)
                // toArray() 强制命中 in(String, Object...)：List<String> 直传会落入 varargs 形成嵌套数组（探针实证）
                : b.group(b.or(b.in("deviceId", realDevices.toArray()), b.eq("deviceId", SENTINEL)));
        return b.and(b.and(tenantEq, deptPart), devicePart);
    }

    /** 关键词路/文档级校验共用判定（委托 PermissionScope 唯一权威代数）。 */
    public boolean allows(PermissionScope scope, String rowTenant, String rowDept, String rowDevice) {
        return scope.allows(rowTenant, rowDept, rowDevice);
    }

    public boolean canReadDocument(AuthContext context, String rowTenant,
                                   String rowDept, String rowDevice) {
        return allows(scopeFor(context), rowTenant, rowDept, rowDevice);
    }

    /** 表达式的人类可读描述（filterDescription 追加口径，测试与审计用）。 */
    public String describeScope(PermissionScope scope) {
        return scope.admin()
                ? "tenant=" + scope.tenantId() + " (ADMIN)"
                : "tenant=" + scope.tenantId() + ", depts=" + scope.deptIds()
                        + ", devices=" + scope.deviceIds();
    }

    private String requireSafeValue(String value, String field) {
        String trimmed = value == null ? null : value.strip();
        if (trimmed == null || trimmed.isEmpty() || trimmed.length() > MAX_VALUE_LENGTH
                || !trimmed.matches(SAFE_VALUE_PATTERN)) {
            // 不降级、不宽容：凭据值异常视为数据损坏，拒绝构建谓词
            throw new IllegalStateException("凭据字段 " + field + " 值不合法（拒绝构建权限谓词）");
        }
        return trimmed;
    }
}
