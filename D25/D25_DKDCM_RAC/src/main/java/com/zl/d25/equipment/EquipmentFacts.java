package com.zl.d25.equipment;

import java.util.List;

/**
 * 当前设备会话中可由用户原话直接支持的设备事实。
 *
 * <p>所有字段均为“未确认事实”，缺失时保持空值，不从模板、常识或模型补齐。</p>
 */
public record EquipmentFacts(
        String deviceModel,
        String deviceIdentifier,
        String deviceType,
        String faultCode,
        List<String> observedSymptoms,
        String onset,
        String frequency,
        String currentState,
        boolean highRiskMentioned
) {
    public boolean hasDeviceIdentity() {
        return hasText(deviceModel) || hasText(deviceType) || hasText(deviceIdentifier);
    }

    public boolean hasSymptoms() {
        return observedSymptoms != null && !observedSymptoms.isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
