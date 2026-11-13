package com.zl.d30.equipment;

import com.zl.d30.equipment.model.CandidateSourceType;
import com.zl.d30.equipment.model.CandidateStatus;
import com.zl.d30.equipment.model.EquipmentTicketCandidate;
import com.zl.d30.equipment.model.TicketCategory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 生成只读工单候选的确定性投影器。
 *
 * <p>摘要只拼接已经识别的用户现象，不补充根因、维修动作或设备事实。
 * 分类是内部候选字典值，未命中时明确返回 {@link TicketCategory#UNCLASSIFIED}。</p>
 */
@Component
public class EquipmentCandidateExtractor {

    /**
     * @param userMessageCount 参与的会话用户消息数量，用于区分单条描述与会话归纳
     */
    public EquipmentTicketCandidate extract(EquipmentFacts facts,
                                            EquipmentClarificationPolicy.Assessment assessment,
                                            int userMessageCount) {
        CandidateStatus status = assessment.required()
                ? CandidateStatus.NEEDS_CLARIFICATION
                : CandidateStatus.CANDIDATE;
        CandidateSourceType source = userMessageCount > 1
                ? CandidateSourceType.CONVERSATION_SUMMARY
                : CandidateSourceType.USER_DESCRIPTION;
        String symptoms = facts.hasSymptoms()
                ? String.join("；", facts.observedSymptoms())
                : "用户尚未提供可观察故障现象";
        return new EquipmentTicketCandidate(
                symptoms,
                classify(facts),
                facts.deviceModel(),
                facts.faultCode(),
                facts.deviceIdentifier(),
                facts.observedSymptoms(),
                assessment.missingInformation(),
                assessment.uncertaintyNote(),
                status,
                source,
                true
        );
    }

    /**
     * 返回带“尚未验证”语义的观察方向；空列表表示当前没有足够信息给出方向。
     */
    public List<String> possibleDirections(EquipmentFacts facts) {
        if (!facts.hasSymptoms()) {
            return List.of();
        }
        String text = String.join(" ", facts.observedSymptoms()).toLowerCase(Locale.ROOT);
        if (facts.highRiskMentioned()) {
            return List.of("先由具备资质的人员确认现场安全状态，再决定排查范围");
        }
        if (containsAny(text, "过热", "温度高", "高温", "发热")) {
            return List.of("核对温度与冷却/散热相关状态（尚未验证，不作为根因结论）");
        }
        if (containsAny(text, "振动", "抖动", "异响", "噪声", "卡滞", "卡住")) {
            return List.of("检查机械负载、连接状态与运行环境（尚未验证，不作为根因结论）");
        }
        return List.of("结合现场观察进一步缩小故障范围（尚未验证，不作为根因结论）");
    }

    private TicketCategory classify(EquipmentFacts facts) {
        String text = String.join(" ", facts.observedSymptoms()).toLowerCase(Locale.ROOT);
        if (facts.highRiskMentioned()) {
            return TicketCategory.SAFETY_RISK;
        }
        if (containsAny(text, "过热", "温度高", "高温", "发热", "漏液", "流量下降")) {
            return TicketCategory.THERMAL_COOLING;
        }
        if (containsAny(text, "振动", "异响", "噪声", "卡滞", "卡住", "过载")) {
            return TicketCategory.MECHANICAL;
        }
        if (containsAny(text, "跳闸", "断电", "漏电", "无法启动")) {
            return TicketCategory.ELECTRICAL;
        }
        if (containsAny(text, "报警", "故障灯", "位置偏差", "漂移", "故障码")) {
            return TicketCategory.CONTROL_SENSOR;
        }
        return TicketCategory.UNCLASSIFIED;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
