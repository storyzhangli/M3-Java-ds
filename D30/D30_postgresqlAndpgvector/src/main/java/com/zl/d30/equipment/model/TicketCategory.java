package com.zl.d30.equipment.model;

/**
 * 工单分类候选的受控字典。
 *
 * <p>这是 AI 候选结果的内部字典，不是正式工单业务分类表；本阶段不会把候选值写入工单。</p>
 */
public enum TicketCategory {
    MECHANICAL,
    THERMAL_COOLING,
    ELECTRICAL,
    CONTROL_SENSOR,
    SAFETY_RISK,
    UNCLASSIFIED
}
