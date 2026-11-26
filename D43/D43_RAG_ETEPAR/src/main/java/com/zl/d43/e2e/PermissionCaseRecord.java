package com.zl.d43.e2e;

/**
 * D43 P4（§6.4 冻结字段序）：权限矩阵 P01–P10 单用例结果。
 * expected/actual 均为冻结拒绝码/放行语义（fixture-truth.expectedFrozen 口径）；
 * verdict 四态；leakage 独立布尔位——出现即 E2E_LEAKAGE_DETECTED P0 熔断（§九）。
 *
 * @param caseId        P01…P10
 * @param principalRole 主体角色（ADMIN/USER/NONE/DISABLED/EXPIRED/INTERNAL）
 * @param endpoint      被访问端点/直调目标
 * @param expected      冻结预期（先冻结后执行）
 * @param actual        实测结果（实码 verbatim）
 * @param verdict       四态
 * @param leakage       越权数据泄漏（true 即 P0 熔断）
 */
public record PermissionCaseRecord(
        String caseId,
        String principalRole,
        String endpoint,
        String expected,
        String actual,
        E2eVerdict verdict,
        boolean leakage) {
}