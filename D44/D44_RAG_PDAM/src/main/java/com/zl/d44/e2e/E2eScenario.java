package com.zl.d44.e2e;

/**
 * P4（任务书 §6.3 冻结，实现期禁私改）：E2E 场景 S01–S12 定义。
 *
 * <p>每个场景携带：断言口径（summary）、前置依赖（dependsOn，逗号分隔场景号，空=独立）。
 * 前置失败时依赖场景记 BLOCKED + E2E_PRECONDITION_BLOCKED（四态语义）。夹具前缀 d44e2e-。</p>
 */
public enum E2eScenario {

    S01("登记+导入 txt：201/200 成功；页文本落库；documentId 前缀正确", ""),
    S02("导入受控夹具：页数=夹具冻结值；关键句在对应页（PDF 受控夹具在库缺失，本轮 txt×2 退化+NOT RUN 注记）", ""),
    S03("切分：480/60 口径；chunk 数与 Gold 映射一致；边界码合法", "S01"),
    S04("向量入库：vector_chunk_index 行=chunk 数；sync SYNCED；UUID 清单登记", "S03"),
    S05("检索 Top-5：Q01–Q10 逐题判题（Gold 命中）；饱和事实如实记录", "S04"),
    S06("RAG 回答（mock）：/rag/answer 成功；引用全部 ∈ 本次来源白名单；excerpt ≤200", "S05"),
    S07("无证据拒答：语料外问题→NO_EVIDENCE（或实码等价拒答），零编造引用", "S05"),
    S08("新版本发布：新向量先写→版本切换→旧向量清理收敛；差异句检索命中 v2；UPDATE_CLEANUP 终态 SYNCED", "S01"),
    S09("文档删除：元数据标记→向量=0→双端确认；检索不再命中该文档", "S01"),
    S10("受控故障恢复：D44_FAULT_INJECTION_* 预设（-D 显式）→删除 FAILED 落库→恢复后 retry 收敛 SYNCED", "S09"),
    S11("权限矩阵：P01–P10 全执行（§6.4），零越权泄漏", ""),
    S12("删除后引用：对已删文档的历史引用请求→CITATION_TARGET_DELETED（实码为准）", "S09");

    private final String assertionSpec;
    private final String dependsOn;

    E2eScenario(String assertionSpec, String dependsOn) {
        this.assertionSpec = assertionSpec;
        this.dependsOn = dependsOn;
    }

    /** 场景号（S01…S12），报告主键。 */
    public String scenarioId() {
        return name();
    }

    /** 断言口径全文（§6.3 冻结原文，报告“定义与结果分离”的定义侧）。 */
    public String assertionSpec() {
        return assertionSpec;
    }

    /** 前置依赖场景号；空串=独立场景。 */
    public String dependsOn() {
        return dependsOn;
    }

    public boolean isIndependent() {
        return dependsOn.isEmpty();
    }
}