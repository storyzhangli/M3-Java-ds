package com.zl.d42.rag.model;

import com.zl.d42.rag.model.RagStatus;

/**
 * 证据评估的业务决策（与链路状态 {@link RagStatus} 双轴分离）。
 *
 * <ul>
 *     <li>{@link #ANSWER}：证据充分，允许进入生成阶段；</li>
 *     <li>{@link #CLARIFY}：缺少必要条件（设备身份/故障现象/安全状态），先澄清；</li>
 *     <li>{@link #REFUSE}：无证据、低相关或证据不充分，拒答且模型零调用；</li>
 *     <li>{@link #CONFLICT}：有效证据对同一事实矛盾，受控返回，等待权威依据。</li>
 * </ul>
 */
public enum RagDecision {

    ANSWER("允许回答"),
    CLARIFY("需要澄清"),
    REFUSE("拒绝回答"),
    CONFLICT("证据冲突");

    private final String description;

    RagDecision(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
