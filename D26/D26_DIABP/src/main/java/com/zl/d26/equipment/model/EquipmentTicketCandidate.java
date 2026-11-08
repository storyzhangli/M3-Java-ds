package com.zl.d26.equipment.model;

import java.util.List;

/**
 * 故障现象摘要与工单分类候选。
 *
 * <p>{@code candidateOnly} 固定为 true。该对象没有映射任何正式工单 Mapper，
 * 也不包含创建、派单或落库命令，只用于 API 返回和后续人工确认。</p>
 */
public record EquipmentTicketCandidate(
        String symptomSummary,
        TicketCategory ticketCategory,
        String deviceModel,
        String faultCode,
        String deviceIdentifier,
        List<String> observedSymptoms,
        List<String> missingInformation,
        String uncertaintyNote,
        CandidateStatus candidateStatus,
        CandidateSourceType sourceType,
        boolean candidateOnly
) {
}
