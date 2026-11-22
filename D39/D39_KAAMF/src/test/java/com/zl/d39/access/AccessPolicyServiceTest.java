package com.zl.d39.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.converter.PrintFilterExpressionConverter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccessPolicyService 谓词代数单测（C01）。
 *
 * <p>核心断言=代数等价：对权限三键组合行集（含缺失键 null），内存判定
 * {@code PermissionScope.allows} 与下推表达式在测试内独立求值器（AND/OR/EQ/IN +
 * Group 解包，语义对齐 pgvector jsonb 键缺失不成立）逐行一致——证明向量路
 * （Filter.Expression 下推）与关键词路（内存谓词）同一 AuthContext 命中集合相同（§3.1）。</p>
 */
class AccessPolicyServiceTest {

    private final AccessPolicyService policy = new AccessPolicyService();

    private static AuthContext user(String tenant, String dept, String... devices) {
        return new AuthContext("u-" + tenant, tenant, dept, Set.of(devices), AuthContext.ROLE_USER);
    }

    private static AuthContext admin(String tenant) {
        return new AuthContext("admin-" + tenant, tenant, "D-ANY", Set.of(), AuthContext.ROLE_ADMIN);
    }

    // ------------------------------------------------------------------
    // scopeFor 结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("USER：dept 集=自身+哨兵，device 集=授权设备+哨兵，admin=false")
    void userScopeStructure() {
        var scope = policy.scopeFor(user("T1", "D1", "DEV-A"));
        assertEquals("T1", scope.tenantId());
        assertFalse(scope.admin());
        assertEquals(Set.of("D1", "ALL"), scope.deptIds());
        assertEquals(Set.of("DEV-A", "ALL"), scope.deviceIds());
    }

    @Test
    @DisplayName("USER 空设备授权：device 集只剩哨兵——仅全设备可见(ALL)文档可读（fail-closed）")
    void emptyDeviceScopeOnlySentinel() {
        var scope = policy.scopeFor(new AuthContext("u", "T1", "D1", Set.of(), AuthContext.ROLE_USER));
        assertEquals(Set.of("ALL"), scope.deviceIds());
        assertTrue(scope.allows("T1", "D1", "ALL"));
        assertFalse(scope.allows("T1", "D1", "DEV-A"));
    }

    @Test
    @DisplayName("ADMIN：租户硬边界保留，bypass 仅作用于部门/设备（H7）")
    void adminScopeBypassesDeptDeviceOnly() {
        var scope = policy.scopeFor(admin("T1"));
        assertTrue(scope.admin());
        assertTrue(scope.allows("T1", null, null), "ADMIN 租户内 bypass 部门/设备（含无 dept/device 行）");
        assertTrue(scope.allows("T1", "D9", "DEV-Z"));
        assertFalse(scope.allows("T2", "D1", "DEV-A"), "ADMIN 不得跨租户");
        assertFalse(scope.allows(null, "D1", "DEV-A"), "无租户行连 ADMIN 也拒绝（fail-closed D3）");
    }

    @Test
    @DisplayName("哨兵化主体（dept=ALL）：退化为部门不限=仅 ALL 标签文档，防御不抛死")
    void sentinelSubjectDegradation() {
        var scope = policy.scopeFor(user("T1", "ALL", "DEV-A"));
        assertTrue(scope.allows("T1", "ALL", "DEV-A"));
        assertFalse(scope.allows("T1", "D1", "DEV-A"));
    }

    @Test
    @DisplayName("凭据值非法（空格/空白/注入字符）：拒绝构建谓词，不降级不宽容")
    void rejectUnsafeCredentialValues() {
        assertThrows(IllegalStateException.class, () -> policy.scopeFor(user("T 1", "D1")));
        assertThrows(IllegalStateException.class, () -> policy.scopeFor(user("", "D1")));
        assertThrows(IllegalStateException.class, () -> policy.scopeFor(admin("a'.b\"or'1'='1")));
    }

    // ------------------------------------------------------------------
    // allows 基础矩阵（缺失键 fail-closed）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("USER：任一缺失键行一律拒绝；跨租户/跨部门/跨设备拒绝；哨兵行放行")
    void userAllowsMatrix() {
        var scope = policy.scopeFor(user("T1", "D1", "DEV-A"));
        assertFalse(scope.allows(null, "D1", "DEV-A"), "缺 tenantId=权限空间外");
        assertFalse(scope.allows("T1", null, "DEV-A"), "缺 deptId");
        assertFalse(scope.allows("T1", "D1", null), "缺 deviceId");
        assertFalse(scope.allows("T2", "D1", "DEV-A"), "跨租户");
        assertFalse(scope.allows("T1", "D2", "DEV-A"), "跨部门");
        assertFalse(scope.allows("T1", "D1", "DEV-B"), "跨设备");
        assertTrue(scope.allows("T1", "D1", "DEV-A"));
        assertTrue(scope.allows("T1", "ALL", "DEV-A"), "部门公开");
        assertTrue(scope.allows("T1", "D1", "ALL"), "设备公开");
        assertTrue(scope.allows("T1", "ALL", "ALL"), "双公开");
    }

    // ------------------------------------------------------------------
    // C01 核心：代数等价——下推表达式独立求值 vs 内存判定，全组合逐行一致
    // ------------------------------------------------------------------

    @Test
    @DisplayName("USER：36 组合行集上，表达式求值与 allows 内存判定逐行等价")
    void expressionAndMemoryPredicateAlgebraicEquivalence() {
        AuthContext context = user("T1", "D1", "DEV-A");
        Filter.Expression expression = policy.vectorFilter(context);
        var scope = policy.scopeFor(context);
        for (String tenant : List.of("T1", "T2", "T1x")) {
            for (String dept : List.of("D1", "D2", "ALL", "D1x")) {
                for (String device : List.of("DEV-A", "DEV-B", "ALL")) {
                    boolean byExpression = evaluate(expression, tenant, dept, device);
                    boolean byMemory = scope.allows(tenant, dept, device);
                    assertEquals(byMemory, byExpression,
                            "代数等价破坏: tenant=%s dept=%s device=%s".formatted(tenant, dept, device));
                }
            }
        }
        // 缺失键（null）行：json 列无该键——eq/in 天然不成立（KP3 实证），两路一致拒绝
        assertFalse(evaluate(expression, null, "D1", "DEV-A"));
        assertFalse(evaluate(expression, "T1", null, "DEV-A"));
        assertFalse(evaluate(expression, "T1", "D1", null));
        assertFalse(scope.allows(null, "D1", "DEV-A"));
    }

    @Test
    @DisplayName("ADMIN：表达式=单租户 eq，含 null dept/device 行两路一致（租户内 bypass）")
    void adminExpressionEquivalence() {
        Filter.Expression expression = policy.vectorFilter(admin("T1"));
        var scope = policy.scopeFor(admin("T1"));
        for (String tenant : List.of("T1", "T2")) {
            assertEquals(scope.allows(tenant, "D1", "DEV-A"),
                    evaluate(expression, tenant, "D1", "DEV-A"));
            assertEquals(scope.allows(tenant, null, null),
                    evaluate(expression, tenant, null, null));
        }
    }

    @Test
    @DisplayName("表达式文本冻结面（PrintFilterExpressionConverter）：三键+哨兵+IN 下推可见")
    void userExpressionTextShape() {
        String text = new PrintFilterExpressionConverter()
                .convertExpression(policy.vectorFilter(user("T1", "D1", "DEV-A", "DEV-B")));
        assertTrue(text.contains("tenantId"), text);
        assertTrue(text.contains("deptId"), text);
        assertTrue(text.contains("deviceId"), text);
        assertTrue(text.contains("ALL"), text);
        assertTrue(text.toLowerCase().contains("in"), "设备授权集应使用 IN 下推: " + text);
    }

    /**
     * D39-R01 真实下推渲染断言（安全回归锁）。
     *
     * <p>背景：P5 矩阵首跑发现 vector 路跨部门/跨租户泄漏——Spring AI 1.1.8
     * PgVectorFilterExpressionConverter 渲染 jsonpath 时仅对 Group 节点加括号，
     * 裸 OR 子树被展平进 AND 链后 SQL 优先级丢失（A &amp;&amp; B || C &amp;&amp; D || E
     * 按 jsonpath 语义解析为 (A&amp;&amp;B) || (C&amp;&amp;D) || E，设备哨兵段独立恒真），
     * PrintFilterExpressionConverter 文本面与代数等价单测均无法暴露该缺陷。
     * 本断言直接冻结 PG 下推 SQL 形态：两个 OR 子树必须各自带括号。</p>
     */
    @Test
    @DisplayName("下推渲染（PgVectorFilterExpressionConverter）：OR 子树必须括号化，防优先级泄漏")
    void pgPushdownRenderKeepsOrGroupsParenthesized() {
        String sql = new PgVectorFilterExpressionConverter()
                .convertExpression(policy.vectorFilter(user("T1", "D1", "DEV-A")));
        // 探针 D39PgFilterProbe 实证的修复后形态：整串冻结（形态漂移即测试失败）
        String expected = "metadata::jsonb @@ '$.\"tenantId\" == \"T1\""
                + " && ($.\"deptId\" == \"D1\" || $.\"deptId\" == \"ALL\")"
                + " && (($.\"deviceId\" == \"DEV-A\") || $.\"deviceId\" == \"ALL\")'::jsonpath";
        assertEquals(expected, sql, "PG 下推 SQL 形态漂移（OR 组括号丢失=优先级泄漏面）");
    }

    @Test
    @DisplayName("下推渲染：ADMIN 单租户 EQ，无 OR 组")
    void pgPushdownRenderAdminSingleEq() {
        String sql = new PgVectorFilterExpressionConverter()
                .convertExpression(policy.vectorFilter(admin("T1")));
        assertEquals("metadata::jsonb @@ '$.\"tenantId\" == \"T1\"'::jsonpath", sql);
    }

    // ------------------------------------------------------------------
    // 测试内独立求值器：AND/OR/EQ/IN + Group 解包（与 Spring AI 1.1.8 记录结构对齐，
    // 刻意不复用生产代码路径，作为等价性反方证据）
    // ------------------------------------------------------------------

    private static boolean evaluate(Filter.Expression expression,
                                    String tenant, String dept, String device) {
        return switch (expression.type()) {
            case AND -> evaluateOperand(expression.left(), tenant, dept, device)
                    && evaluateOperand(expression.right(), tenant, dept, device);
            case OR -> evaluateOperand(expression.left(), tenant, dept, device)
                    || evaluateOperand(expression.right(), tenant, dept, device);
            case EQ -> {
                String row = rowValue(keyOf(expression), tenant, dept, device);
                yield row != null && row.equals(String.valueOf(valueOf(expression).value()));
            }
            case IN -> {
                String row = rowValue(keyOf(expression), tenant, dept, device);
                yield row != null && inList(expression).contains(row);
            }
            default -> throw new IllegalStateException("单测求值器未覆盖的表达式类型: " + expression.type());
        };
    }

    private static boolean evaluateOperand(Filter.Operand operand,
                                           String tenant, String dept, String device) {
        if (operand instanceof Filter.Group group) {
            return evaluate(group.content(), tenant, dept, device);
        }
        if (operand instanceof Filter.Expression expression) {
            return evaluate(expression, tenant, dept, device);
        }
        throw new IllegalStateException("意外操作数类型: " + operand.getClass());
    }

    private static String keyOf(Filter.Expression expression) {
        return ((Filter.Key) expression.left()).key();
    }

    private static Filter.Value valueOf(Filter.Expression expression) {
        return (Filter.Value) expression.right();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> inList(Filter.Expression expression) {
        Object content = valueOf(expression).value();
        if (content instanceof List<?> list) {
            return (List<Object>) list;
        }
        // 单元素 in 的容错形态
        return List.of(content);
    }

    private static String rowValue(String key, String tenant, String dept, String device) {
        return switch (key) {
            case "tenantId" -> tenant;
            case "deptId" -> dept;
            case "deviceId" -> device;
            default -> throw new IllegalStateException("意外过滤键: " + key);
        };
    }
}
