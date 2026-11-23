package com.zl.d40.rag.service;

import com.zl.d40.equipment.EquipmentFacts;
import com.zl.d40.rag.model.RagReasonCode;
import com.zl.d40.rag.model.RetrievedChunk;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后校验器：区分「引用 ID 合法性」与「事实支持关系」。
 *
 * <p>引用 ID 合法性由 {@code CitationRegistry.validateAndStrip} 负责（删未知编号并计数）；
 * 本类负责事实支持关系：答案中出现的<b>新故障码</b>或<b>数值参数</b>必须能映射到已接受证据，
 * 否则视为凭空生成（UNSUPPORTED_CLAIM），由编排层转为受控失败。</p>
 */
@Service
public class PostGenerationValidator {

    /** 故障码形态：E5 / F12 / ALM-03 / ERR42 / SP3 等。 */
    private static final Pattern FAULT_CODE_PATTERN = Pattern.compile(
            "\\b(?:E|F|ALM|ERR|SP)[-_]?\\d{1,8}\\b", Pattern.CASE_INSENSITIVE);

    /** 数值参数：数字（可带小数）+ 工程单位（大小写不敏感，如 MPa/mpa 均为压力单位）。 */
    private static final Pattern NUMERIC_PARAM_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:MPa|kPa|bar|V|A|Ω|℃|°C|rpm|mm|Hz|ms|kg|L|%|W|kW)",
            Pattern.CASE_INSENSITIVE);

    /** 参数前后窗口（码点），用于定位参数串并核对证据支撑。 */
    private static final int PARAM_WINDOW = 24;

    /** 答案中的合法引用编号（清洗后保留），窗口比对前剔除，避免干扰证据子串匹配。 */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[s\\d{1,3}]");

    /**
     * 无来源 URL / 本机路径 / 危险协议：证据不提供 URL，答案中出现即视为模型/用户拼接（FABRICATED_URL）。
     * 覆盖 http(s) 链接、javascript:/data:/file:/ftp: 危险协议、Windows 盘符绝对路径。
     */
    private static final Pattern FABRICATED_URL_PATTERN = Pattern.compile(
            "(?:https?://|javascript:|data:|file:|ftp:)[^\\s]*"
                    + "|[A-Za-z]:[\\\\/]\\S*",
            Pattern.CASE_INSENSITIVE);

    /**
     * 校验生成答案的事实支持关系。
     *
     * @param answer          引用清洗后的答案（未知编号已被删除）
     * @param acceptedChunks  证据评估通过的证据（答案中每个事实声明必须可映射到这里）
     * @param facts           用户问题提取的设备事实（问题自带故障码不算凭空生成）
     * @return 校验结果（通过 / 失败原因）
     */
    public ValidationOutcome validate(String answer, List<RetrievedChunk> acceptedChunks,
                                      EquipmentFacts facts) {
        if (answer == null || answer.isBlank()) {
            return ValidationOutcome.failure(RagReasonCode.GENERATION_VALIDATION_FAILED,
                    "生成结果为空");
        }
        List<String> evidenceTexts = acceptedChunks == null ? List.of()
                : acceptedChunks.stream()
                .map(chunk -> lower(chunk.content() == null ? "" : chunk.content()))
                .filter(StringUtils::hasText)
                .toList();
        String lowerAnswer = lower(answer);

        // 1. 新故障码：不属于问题本身、也不出现在任何已接受证据中
        String questionFaultCode = facts == null ? null
                : (StringUtils.hasText(facts.faultCode()) ? facts.faultCode() : null);
        Matcher codeMatcher = FAULT_CODE_PATTERN.matcher(lowerAnswer);
        while (codeMatcher.find()) {
            String code = codeMatcher.group().toUpperCase(Locale.ROOT);
            if (questionFaultCode != null && code.equalsIgnoreCase(questionFaultCode)) {
                continue;
            }
            if (evidenceTexts.stream().anyMatch(text -> text.contains(lower(code)))) {
                continue;
            }
            return ValidationOutcome.failure(RagReasonCode.UNSUPPORTED_CLAIM,
                    "答案中出现证据不支持的故障码: " + code);
        }

        // 2. 数值参数：参数片段（剔除引用编号后）必须出现在已接受证据中
        Matcher paramMatcher = NUMERIC_PARAM_PATTERN.matcher(lowerAnswer);
        while (paramMatcher.find()) {
            int start = Math.max(0, paramMatcher.start() - PARAM_WINDOW);
            int end = Math.min(lowerAnswer.length(), paramMatcher.end() + PARAM_WINDOW);
            String window = CITATION_MARKER.matcher(lowerAnswer.substring(start, end))
                    .replaceAll("");
            boolean supported = evidenceTexts.stream().anyMatch(text -> text.contains(window));
            if (!supported) {
                return ValidationOutcome.failure(RagReasonCode.UNSUPPORTED_CLAIM,
                        "答案中出现证据不支持的数值参数: " + paramMatcher.group());
            }
        }

        // 3. 无来源 URL / 本机路径 / 危险协议：证据不提供 URL，答案中出现即视为拼接
        Matcher urlMatcher = FABRICATED_URL_PATTERN.matcher(lowerAnswer);
        if (urlMatcher.find()) {
            return ValidationOutcome.failure(RagReasonCode.FABRICATED_URL,
                    "答案中出现无来源或危险协议的链接/本机路径: " + urlMatcher.group());
        }
        return ValidationOutcome.ok();
    }

    private String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * 校验结果：通过 / 失败（原因码 + 可读说明）。
     */
    public record ValidationOutcome(boolean passed, RagReasonCode reasonCode, String detail) {

        public static ValidationOutcome ok() {
            return new ValidationOutcome(true, null, null);
        }

        public static ValidationOutcome failure(RagReasonCode reasonCode, String detail) {
            return new ValidationOutcome(false, reasonCode, detail);
        }
    }
}
