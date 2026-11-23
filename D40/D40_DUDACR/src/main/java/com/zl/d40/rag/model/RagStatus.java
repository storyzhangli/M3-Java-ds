package com.zl.d40.rag.model;

import com.zl.d40.rag.model.RagDecision;
import com.zl.d40.rag.model.RagResponse;

/**
 * RAG 链路状态（七态：D33 五态 + 证据决策引入的澄清/冲突两态，可互相区分，禁止混用）。
 *
 * <ul>
 *     <li>{@link #NO_HIT}：检索成功但无命中（空检索）；</li>
 *     <li>{@link #NO_USABLE_CONTEXT}：检索有结果但全部被过滤或预算无法容纳；</li>
 *     <li>{@link #CLARIFY_REQUIRED}：证据评估判定缺少必要条件，需要澄清（模型零调用）；</li>
 *     <li>{@link #EVIDENCE_CONFLICT}：有效证据矛盾，受控返回冲突（模型零调用）；</li>
 *     <li>{@link #RETRIEVAL_FAILED}：检索执行异常（基础设施故障）；</li>
 *     <li>{@link #GENERATION_FAILED}：生成执行失败（含生成后校验不通过）；</li>
 *     <li>{@link #SUCCESS}：回答已生成且来源可回溯。</li>
 * </ul>
 *
 * <p>业务决策（ANSWER/CLARIFY/REFUSE/CONFLICT）见 {@link RagDecision}，由
 * {@link RagResponse#decision()} 承载，与本状态双轴分离。</p>
 */
public enum RagStatus {

    SUCCESS("回答成功生成"),
    NO_HIT("检索成功但无命中"),
    NO_USABLE_CONTEXT("检索有结果但无可用上下文"),
    CLARIFY_REQUIRED("缺少必要条件，需要澄清"),
    EVIDENCE_CONFLICT("有效证据互相矛盾"),
    RETRIEVAL_FAILED("检索执行失败"),
    GENERATION_FAILED("生成执行失败");

    private final String description;

    RagStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
