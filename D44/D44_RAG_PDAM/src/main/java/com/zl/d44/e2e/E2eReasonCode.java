package com.zl.d44.e2e;

/**
 * P4（任务书 §6.5 冻结码表，共 9 码）：E2E 层统一原因码。
 * 未登记情形一律按失败暴露（E2E_ASSERTION_FAILED 细分进 assertions[]），禁止静默吞码。
 */
public enum E2eReasonCode {

    E2E_FIXTURE_MISSING("夹具缺失/指纹不符（执行前拦截）"),
    E2E_PRECONDITION_BLOCKED("前置场景失败导致本场景不可执行"),
    E2E_ASSERTION_FAILED("断言不符（细分见 assertions[]）"),
    E2E_STORAGE_UNAVAILABLE("MySQL/PG 不可达（环境阻断，非代码失败）"),
    E2E_AUTH_CODE_UNKNOWN("出现未登记拒绝码（按失败暴露并补登记）"),
    E2E_LEAKAGE_DETECTED("越权数据泄漏（P0 级，立即熔断）"),
    E2E_SATURATION_NOTED("指标饱和注记（非失败，报告标注用）"),
    E2E_SEMANTIC_NOT_AUTHORIZED("语义评估未授权（NOT RUN 依据）"),
    E2E_REPORT_WRITE_FAILED("报告落盘失败");

    private final String description;

    E2eReasonCode(String description) {
        this.description = description;
    }

    public String code() {
        return name();
    }

    public String description() {
        return description;
    }

    /** 字符串码 → 枚举；未登记返回 null（调用方必须按失败暴露，不默认归类）。 */
    public static E2eReasonCode find(String code) {
        for (E2eReasonCode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        return null;
    }
}