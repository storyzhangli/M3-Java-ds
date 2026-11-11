package com.zl.d28.equipment.dto;

import com.zl.d28.dto.CitationDto;
import com.zl.d28.equipment.model.EquipmentTicketCandidate;

import java.util.List;

/**
 * 设备客服/技术工程师对话响应。
 *
 * @param sessionId             服务端生成或校验后的会话 ID
 * @param scene                 设备角色场景
 * @param templateId            实际使用的模板 ID
 * @param templateVersion       实际使用的模板版本
 * @param templateUpdatedAt     实际模板最近更新时间
 * @param mode                  实际处理模式
 * @param model                 模型或规则处理器标识
 * @param mocked                是否离线模拟/规则处理
 * @param answer                面向用户的回答或澄清问题
 * @param clarificationRequired 当前是否必须补充关键信息
 * @param knownFacts            仅由用户原话支持的已知事实
 * @param missingInformation    仍缺少的信息
 * @param possibleDirections    尚未验证的可能排查方向
 * @param uncertaintyNote       当前不确定性说明
 * @param candidateExtraction   只读工单候选，不触发正式写入
 * @param citations             仅来自真实检索的引用；无证据时为空列表
 */
public record EquipmentChatResponse(
        String sessionId,
        String scene,
        String templateId,
        Integer templateVersion,
        String templateUpdatedAt,
        String mode,
        String model,
        Boolean mocked,
        String answer,
        boolean clarificationRequired,
        List<String> knownFacts,
        List<String> missingInformation,
        List<String> possibleDirections,
        String uncertaintyNote,
        EquipmentTicketCandidate candidateExtraction,
        List<CitationDto> citations
) {
}
