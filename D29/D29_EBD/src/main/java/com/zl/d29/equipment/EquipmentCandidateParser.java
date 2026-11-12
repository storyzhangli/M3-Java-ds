package com.zl.d29.equipment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d29.equipment.model.CandidateSourceType;
import com.zl.d29.equipment.model.CandidateStatus;
import com.zl.d29.equipment.model.EquipmentTicketCandidate;
import com.zl.d29.equipment.model.TicketCategory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化候选 JSON 的严格解析与字段校验器。
 *
 * <p>供真实模型的结构化输出适配层或测试使用；默认设备对话使用本地规则投影，
 * 因此不会为了生成候选额外调用一次 DeepSeek。解析失败和未知枚举均受控失败，
 * 不将不完整 JSON 当作正式业务对象。</p>
 */
@Component
public class EquipmentCandidateParser {

    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_LIST_SIZE = 20;
    private static final int MAX_LIST_ITEM_LENGTH = 200;

    private final ObjectMapper objectMapper;

    public EquipmentCandidateParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模型候选 JSON；任何缺失、非法枚举、未知字段类型或超长字段均抛出
     * {@link IllegalArgumentException}，由全局异常处理转成 4001。
     */
    public EquipmentTicketCandidate parse(String json) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalArgumentException("候选 JSON 不能为空");
        }
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("候选 JSON 顶层必须是对象");
            }
            String summary = requiredText(root, "symptomSummary", MAX_SUMMARY_LENGTH);
            TicketCategory category = parseEnum(root, "ticketCategory", TicketCategory.class);
            CandidateStatus status = parseEnum(root, "candidateStatus", CandidateStatus.class);
            CandidateSourceType source = parseEnum(root, "sourceType", CandidateSourceType.class);
            String uncertainty = requiredText(root, "uncertaintyNote", MAX_SUMMARY_LENGTH);
            List<String> symptoms = stringList(root, "observedSymptoms");
            List<String> missing = stringList(root, "missingInformation");
            if (status == CandidateStatus.NEEDS_CLARIFICATION && missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "candidateStatus=NEEDS_CLARIFICATION 时 missingInformation 不能为空");
            }
            return new EquipmentTicketCandidate(
                    summary,
                    category,
                    optionalText(root, "deviceModel", 100),
                    optionalText(root, "faultCode", 64),
                    optionalText(root, "deviceIdentifier", 100),
                    symptoms,
                    missing,
                    uncertainty,
                    status,
                    source,
                    true
            );
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("候选 JSON 解析失败，请检查格式与字段类型", ex);
        }
    }

    private String requiredText(JsonNode root, String field, int maxLength) {
        String value = optionalText(root, field, maxLength);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("候选字段缺失: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("候选字段类型非法: " + field);
        }
        String value = node.asText().strip();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("候选字段超长: " + field);
        }
        return value;
    }

    private <E extends Enum<E>> E parseEnum(JsonNode root, String field, Class<E> type) {
        String value = optionalText(root, field, 64);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("候选枚举字段缺失: " + field);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("候选枚举值非法: " + field + "=" + value);
        }
    }

    private List<String> stringList(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("候选字段必须是数组: " + field);
        }
        if (node.size() > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("候选数组元素过多: " + field);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("候选数组元素必须是字符串: " + field);
            }
            String value = item.asText().strip();
            if (value.length() > MAX_LIST_ITEM_LENGTH) {
                throw new IllegalArgumentException("候选数组元素超长: " + field);
            }
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
