package com.zl.d44.e2e;

/**
 * P4：统一四态判定（任务书 §6.3/§6.4/门禁 G-2/G-3 口径；retestStatus 仅允许 PASS/FAIL/BLOCKED/NOT RUN）。
 */
public enum E2eVerdict {

    PASS("通过"),
    FAIL("失败（有断言不符，进 bad-case 分类）"),
    BLOCKED("被前置或环境阻断（须环境原因+复跑计划）"),
    NOT_RUN("未执行（须原因，如 PDF 夹具缺失/语义未授权）");

    private final String label;

    E2eVerdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 字符串安全解析（JSON 回读用）；非法值抛失败不默认兜底。 */
    public static E2eVerdict parse(String raw) {
        for (E2eVerdict verdict : values()) {
            if (verdict.name().equalsIgnoreCase(raw)) {
                return verdict;
            }
        }
        throw new IllegalArgumentException("非法 E2eVerdict: " + raw);
    }
}