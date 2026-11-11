package com.zl.d28.equipment;

import com.zl.d28.equipment.EquipmentFacts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备故障场景的澄清优先级规则。
 *
 * <p>只问能显著减少歧义的一到两个信息，不要求用户一次性填完整表单。</p>
 */
@Component
public class EquipmentClarificationPolicy {

    /** 澄清判断结果；{@code question} 在无需澄清时为空串。 */
    public record Assessment(
            boolean required,
            List<String> missingInformation,
            String question,
            String uncertaintyNote
    ) {
    }

    public Assessment assess(EquipmentFacts facts) {
        List<String> missing = new ArrayList<>();
        if (!facts.hasDeviceIdentity()) {
            missing.add("设备型号或可识别的设备身份");
        }
        if (!facts.hasSymptoms()) {
            missing.add("具体故障现象");
        }
        if (facts.highRiskMentioned() && !hasSafetyState(facts.currentState())) {
            missing.add("当前安全状态（是否停机、断电或已隔离）");
        }
        if (facts.faultCode() == null) {
            missing.add("故障码（未提供；如设备有报警码，可原样补充）");
        }

        if (!facts.hasDeviceIdentity()) {
            return new Assessment(true, List.copyOf(missing),
                    "请先补充设备型号，或提供设备类型、编号/产线位置等可识别身份的信息。",
                    "目前还不能确定具体故障原因，需要先确认设备身份。");
        }
        if (!facts.hasSymptoms()) {
            return new Assessment(true, List.copyOf(missing),
                    "请描述设备当前可观察到的现象，例如报警、异响、振动、温度、停机或运行异常。",
                    "目前还不能确定具体故障原因，缺少可观察的故障现象。");
        }
        if (facts.highRiskMentioned() && !hasSafetyState(facts.currentState())) {
            return new Assessment(true, List.copyOf(missing),
                    "请先确认设备是否已经停机、断电或隔离；在状态不明确时不要靠近或拆检设备。",
                    "存在需要现场安全确认的因素，当前不提供高风险操作步骤。");
        }
        return new Assessment(false, List.copyOf(missing), "",
                "当前判断仅基于用户描述，尚未经过现场验证；未提供故障码时不会推测具体代码或根因。");
    }

    private boolean hasSafetyState(String state) {
        return state != null && !state.isBlank();
    }
}
