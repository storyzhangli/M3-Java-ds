package com.zl.d25.equipment;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户原话中提取设备事实的确定性规则组件。
 *
 * <p>只识别显式表达或可直接观察的文本片段，不推断根因、不补全故障码、
 * 不把“可能”“也许”后的猜测当作事实。它同时用于 Mock 演示和真实模型调用前的
 * 上下文归纳，默认不产生额外模型费用。</p>
 */
@Component
public class EquipmentInformationExtractor {

    private static final int MAX_FACT_LENGTH = 160;

    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "(?:设备型号|型号|机型)\\s*(?:是|为|[:：])?\\s*([A-Za-z0-9][A-Za-z0-9._/#-]{1,31})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "(?:设备编号|序列号|产线位置)\\s*(?:是|为|[:：])?\\s*([A-Za-z0-9\\u4e00-\\u9fff._/#-]{1,32})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAULT_CODE_PATTERN = Pattern.compile(
            "(?:故障码|报警码|报警号|错误码|代码)\\s*(?:是|为|[:：])?\\s*([A-Za-z]{0,8}[-_]?\\d{2,12})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STANDALONE_CODE_PATTERN = Pattern.compile(
            "\\b(?:E|F|ALM|ERR|SP)[-_]?\\d{2,8}\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ONSET_PATTERN = Pattern.compile(
            "(?:今天|昨晚|刚才|昨天|近日|最近|本周|上周|凌晨|上午|下午|晚上|启动后|运行后|开机后)[^，,。；;\\n]{0,40}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FREQUENCY_PATTERN = Pattern.compile(
            "(?:偶发|持续|反复|经常|每次|每天|每小时|间歇|时有时无)[^，,。；;\\n]{0,40}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATE_PATTERN = Pattern.compile(
            "(?:已停机|停机|运行中|待机|报警中|已隔离|已断电|断电|空载|满载)[^，,。；;\\n]{0,40}",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> DEVICE_TYPES = List.of(
            "数控机床", "加工中心", "伺服电机", "冷却系统", "机器人", "变频器",
            "输送线", "液压系统", "空压机", "水泵", "PLC", "传感器"
    );

    private static final List<String> SYMPTOM_KEYWORDS = List.of(
            "过热", "温度高", "高温", "发热", "报警", "故障灯", "异响", "噪声",
            "振动", "抖动", "停机", "不转", "卡滞", "卡住", "漏液", "漏油",
            "压力不足", "压力异常", "流量下降", "冒烟", "跳闸", "断电", "位置偏差",
            "漂移", "过载", "无法启动", "速度异常"
    );

    private static final List<String> HIGH_RISK_KEYWORDS = List.of(
            "带电", "高压", "压力系统", "带压", "高温", "冒烟", "起火", "高速旋转",
            "旋转部件", "漏电", "触电"
    );

    /**
     * 按会话顺序合并用户消息；后出现的事实覆盖同类的旧值，现象采用去重并集。
     */
    public EquipmentFacts extract(List<String> userMessages) {
        List<String> safeMessages = userMessages == null ? List.of() : userMessages;
        String deviceModel = null;
        String deviceIdentifier = null;
        String deviceType = null;
        String faultCode = null;
        String onset = null;
        String frequency = null;
        String currentState = null;
        Set<String> symptoms = new LinkedHashSet<>();
        boolean highRisk = false;

        for (String message : safeMessages) {
            if (!StringUtils.hasText(message)) {
                continue;
            }
            String text = message.strip();
            String lower = text.toLowerCase(Locale.ROOT);

            deviceModel = firstNonNull(matchGroupOne(MODEL_PATTERN, text), deviceModel);
            String explicitId = matchIdentifier(text);
            deviceIdentifier = firstNonNull(explicitId, deviceIdentifier);
            deviceType = firstNonNull(matchDeviceType(text), deviceType);
            faultCode = firstNonNull(matchFaultCode(text), faultCode);
            onset = firstNonNull(match(ONSET_PATTERN, text), onset);
            frequency = firstNonNull(match(FREQUENCY_PATTERN, text), frequency);
            currentState = firstNonNull(match(STATE_PATTERN, text), currentState);

            for (String clause : splitClauses(text)) {
                String normalized = clause.replaceAll("\\s+", " ").strip();
                if (normalized.isBlank()) {
                    continue;
                }
                String clauseLower = normalized.toLowerCase(Locale.ROOT);
                if (SYMPTOM_KEYWORDS.stream().anyMatch(clauseLower::contains)) {
                    symptoms.add(truncate(normalized));
                }
            }
            highRisk = highRisk || HIGH_RISK_KEYWORDS.stream().anyMatch(lower::contains);
        }

        return new EquipmentFacts(deviceModel, deviceIdentifier, deviceType, faultCode,
                List.copyOf(symptoms), onset, frequency, currentState, highRisk);
    }

    private String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? truncate(matcher.group()) : null;
    }

    private String matchGroupOne(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? truncate(matcher.group(1)) : null;
    }

    private String matchIdentifier(String text) {
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
        return matcher.find() ? truncate(matcher.group(1)) : null;
    }

    private String matchFaultCode(String text) {
        Matcher explicit = FAULT_CODE_PATTERN.matcher(text);
        if (explicit.find()) {
            return truncate(explicit.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher standalone = STANDALONE_CODE_PATTERN.matcher(text);
        return standalone.find() ? truncate(standalone.group().toUpperCase(Locale.ROOT)) : null;
    }

    private String matchDeviceType(String text) {
        return DEVICE_TYPES.stream().filter(text::contains).findFirst().orElse(null);
    }

    private List<String> splitClauses(String text) {
        List<String> clauses = new ArrayList<>();
        for (String part : text.split("[，,。；;！？!?\\n]")) {
            if (StringUtils.hasText(part)) {
                clauses.add(part);
            }
        }
        return clauses;
    }

    private String firstNonNull(String candidate, String previous) {
        return StringUtils.hasText(candidate) ? candidate : previous;
    }

    private String truncate(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_FACT_LENGTH
                ? normalized
                : normalized.substring(0, MAX_FACT_LENGTH);
    }
}
