package com.zl.d33.rag.model;

/**
 * RAG 链路状态（五态，可互相区分，禁止混用）。
 *
 * <ul>
 *     <li>{@link #NO_HIT}：检索成功但无命中（空检索）；</li>
 *     <li>{@link #NO_USABLE_CONTEXT}：检索有结果但全部被过滤或预算无法容纳；</li>
 *     <li>{@link #RETRIEVAL_FAILED}：检索执行异常（基础设施故障）；</li>
 *     <li>{@link #GENERATION_FAILED}：生成执行失败（含模型超时）；</li>
 *     <li>{@link #SUCCESS}：回答已生成且来源可回溯。</li>
 * </ul>
 */
public enum RagStatus {

    SUCCESS("回答成功生成"),
    NO_HIT("检索成功但无命中"),
    NO_USABLE_CONTEXT("检索有结果但无可用上下文"),
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
