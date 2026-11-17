package com.zl.d34.rag.service;

import com.zl.d34.equipment.EquipmentClarificationPolicy;
import com.zl.d34.equipment.EquipmentFacts;
import com.zl.d34.knowledge.document.DocumentLifecycleStatus;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.EvidenceAssessment;
import com.zl.d34.rag.model.RagDecision;
import com.zl.d34.rag.model.RagReasonCode;
import com.zl.d34.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据评估门禁：对检索候选做生命周期过滤、型号匹配、相关性、澄清、冲突与充分性判定。
 *
 * <p>边界：不调用模型、不触发新检索；只消费 {@link RetrievedChunk} 与确定性规则。
 * 相似度分数只是检索信号，不能证明证据充分；冲突判定只认结构化信号
 * （"参数名 应为/设定为 数值 单位"值对提取），禁止按版本号一刀切。</p>
 */
@Slf4j
@Service
public class EvidenceAssessmentService {

    /** 参数值对提取："压力阈值应设定为 2.5MPa" → topic="压力阈值"、value="2.5"、unit="MPa"。 */
    static final Pattern PARAM_VALUE_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fffA-Za-z][\\u4e00-\\u9fffA-Za-z0-9/·]{0,15}?)\\s*"
                    + "(?:应设定为|设定为|应设为|设为|规定为|应为|是)\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*([A-Za-zμΩ℃%]{0,6})");

    /** 型号标识提取（供型号匹配过滤，避免把故障码 E5 等误判为型号）。 */
    static final Pattern MODEL_CODE_PATTERN = Pattern.compile(
            "\\b([A-Za-z]{1,8}[-_]?\\d{1,8})\\b");

    /** 故障码形态（与设备事实提取器口径一致，用于排除型号误提取）。 */
    private static final Pattern FAULT_CODE_SHAPE = Pattern.compile(
            "(?:E|F|ALM|ERR|SP)[-_]?\\d{2,8}", Pattern.CASE_INSENSITIVE);

    private final EquipmentClarificationPolicy clarificationPolicy;

    private final RagProperties properties;

    public EvidenceAssessmentService(EquipmentClarificationPolicy clarificationPolicy,
                                     RagProperties properties) {
        this.clarificationPolicy = clarificationPolicy;
        this.properties = properties;
    }

    /**
     * 对候选证据执行确定性评估。
     *
     * @param retrieved 检索命中（未过滤）
     * @param facts     从用户原话提取的设备事实（可为 null，视为无澄清需求）
     */
    public EvidenceAssessment assess(List<RetrievedChunk> retrieved, EquipmentFacts facts) {
        LocalDateTime now = LocalDateTime.now();
        List<EvidenceAssessment.AssessmentDetail> details = new ArrayList<>();

        // 1. 无证据
        if (retrieved == null || retrieved.isEmpty()) {
            return refuse(RagReasonCode.NO_EVIDENCE, "知识库无任何检索结果",
                    List.of(), List.of(), List.of(), EvidenceAssessment.UNCOVERED);
        }
        List<String> evidenceIds = retrieved.stream()
                .map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList();

        // 2. 生命周期过滤：DEPRECATED/EXPIRED/未生效一律拒绝
        List<RetrievedChunk> lifecycleOk = new ArrayList<>();
        List<String> rejectedIds = new ArrayList<>();
        for (RetrievedChunk chunk : retrieved) {
            DocumentLifecycleStatus lifecycle = parseLifecycle(chunk.lifecycleStatus());
            if (lifecycle != null && !lifecycle.isUsableForEvidence(now, chunk.effectiveAt(),
                    chunk.expiresAt())) {
                rejectedIds.add(chunk.chunkId());
                details.add(new EvidenceAssessment.AssessmentDetail(
                        "lifecycle-filtered", chunk.chunkId() + ":" + chunk.lifecycleStatus()));
                continue;
            }
            lifecycleOk.add(chunk);
        }
        if (lifecycleOk.isEmpty()) {
            return refuse(RagReasonCode.INSUFFICIENT_EVIDENCE,
                    "所有命中证据均已废弃、未生效或过期",
                    evidenceIds, List.of(), rejectedIds, EvidenceAssessment.UNCOVERED);
        }

        // 3. 设备型号匹配：问题已明确型号时，显式属于其他型号的资料拒绝
        List<RetrievedChunk> modelOk = new ArrayList<>();
        if (facts != null && StringUtils.hasText(facts.deviceModel())) {
            String targetModel = normalizeModel(facts.deviceModel());
            for (RetrievedChunk chunk : lifecycleOk) {
                String chunkModel = extractModelCode(chunk.title() == null ? "" : chunk.title());
                if (chunkModel == null) {
                    chunkModel = extractModelCode(chunk.content() == null ? "" : chunk.content());
                }
                if (chunkModel != null && !chunkModel.equalsIgnoreCase(targetModel)) {
                    rejectedIds.add(chunk.chunkId());
                    details.add(new EvidenceAssessment.AssessmentDetail(
                            "model-mismatch", chunk.chunkId() + ":" + chunkModel));
                    continue;
                }
                modelOk.add(chunk);
            }
        } else {
            modelOk.addAll(lifecycleOk);
        }
        if (modelOk.isEmpty()) {
            return refuse(RagReasonCode.INSUFFICIENT_EVIDENCE,
                    "全部命中证据均为其他设备型号的资料",
                    evidenceIds, List.of(), rejectedIds, EvidenceAssessment.UNCOVERED);
        }

        // 4. 相关性：相似度分数只是检索信号，低于相关性阈值视为低相关
        double relevantScore = properties.getEvidence().getRelevantScore();
        List<RetrievedChunk> related = modelOk.stream()
                .filter(chunk -> chunk.normalizedScore() >= relevantScore)
                .sorted(Comparator.comparingDouble(RetrievedChunk::normalizedScore).reversed())
                .toList();
        if (related.isEmpty()) {
            details.add(new EvidenceAssessment.AssessmentDetail(
                    "relevance", "all below relevant-score=" + relevantScore));
            return refuse(RagReasonCode.LOW_RELEVANCE,
                    "检索结果仅关键词相似，不能支持该问题",
                    evidenceIds, List.of(), rejectedIds, EvidenceAssessment.UNCOVERED);
        }

        // 5. 澄清：缺必要条件时先澄清，不做冲突与充分性判定（信息不足时的任何结论都可能误导）
        if (facts != null) {
            EquipmentClarificationPolicy.Assessment clarify = clarificationPolicy.assess(facts);
            if (clarify.required()) {
                details.add(new EvidenceAssessment.AssessmentDetail(
                        "clarification", clarify.question()));
                details.add(new EvidenceAssessment.AssessmentDetail(
                        "uncertainty-note", clarify.uncertaintyNote()));
                return new EvidenceAssessment(RagDecision.CLARIFY,
                        RagReasonCode.MISSING_REQUIRED_CONTEXT, clarify.question(),
                        evidenceIds, List.of(), rejectedIds, List.of(),
                        clarify.missingInformation(), details, List.of(),
                        EvidenceAssessment.UNCOVERED);
            }
        }

        // 6. 冲突检测：仅对 ACTIVE 且相关证据提取结构化参数值对
        List<EvidenceAssessment.ConflictGroup> conflictGroups =
                detectConflicts(related, now);
        if (!conflictGroups.isEmpty()) {
            details.add(new EvidenceAssessment.AssessmentDetail(
                    "conflict-groups", Integer.toString(conflictGroups.size())));
            return new EvidenceAssessment(RagDecision.CONFLICT,
                    RagReasonCode.EVIDENCE_CONFLICT,
                    "有效证据对同一事实给出矛盾内容，等待权威依据或人工确认",
                    evidenceIds, List.of(), rejectedIds, conflictGroups,
                    List.of(), details, List.of(), EvidenceAssessment.UNCOVERED);
        }

        // 7. 充分性：必须有词面支持信号（故障码/设备类型/症状重合），否则仅关键词相似
        List<RetrievedChunk> supported = related.stream()
                .filter(chunk -> hasFactSupport(chunk, facts))
                .toList();
        if (supported.isEmpty()) {
            details.add(new EvidenceAssessment.AssessmentDetail(
                    "support", "no fact-level support signal in related chunks"));
            return refuse(RagReasonCode.LOW_RELEVANCE,
                    "检索结果与问题仅关键词相似，缺乏可支持结论的事实信号",
                    evidenceIds, List.of(), rejectedIds, EvidenceAssessment.UNCOVERED);
        }
        boolean direct = supported.stream().anyMatch(chunk -> hasDirectSupport(chunk, facts));
        String sufficiency = direct
                ? EvidenceAssessment.DIRECT : EvidenceAssessment.LIMITED_INFERENCE;
        details.add(new EvidenceAssessment.AssessmentDetail(
                "sufficiency", sufficiency));
        details.add(new EvidenceAssessment.AssessmentDetail(
                "accepted", Integer.toString(supported.size()) + "/" + related.size()));

        List<String> acceptedIds = supported.stream()
                .map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList();
        return new EvidenceAssessment(RagDecision.ANSWER, null,
                "证据充分，允许生成回答", evidenceIds, acceptedIds, rejectedIds,
                List.of(), List.of(), details, List.copyOf(supported), sufficiency);
    }

    /**
     * 结构化冲突检测：同一参数名在两条及以上 ACTIVE 证据中出现不同数值 → 冲突。
     * 不同设备型号的资料在型号过滤阶段已被拒绝，不会误判为冲突。
     */
    private List<EvidenceAssessment.ConflictGroup> detectConflicts(List<RetrievedChunk> related,
                                                                   LocalDateTime now) {
        Map<String, Map<String, String>> paramValues = new LinkedHashMap<>();
        for (RetrievedChunk chunk : related) {
            if (chunk.chunkId() == null || chunk.content() == null) {
                continue;
            }
            Map<String, String> extracted = extractParamValues(chunk.content());
            for (Map.Entry<String, String> entry : extracted.entrySet()) {
                paramValues.computeIfAbsent(entry.getKey(), key -> new LinkedHashMap<>())
                        .putIfAbsent(chunk.chunkId(), entry.getValue());
            }
        }
        List<EvidenceAssessment.ConflictGroup> groups = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : paramValues.entrySet()) {
            Map<String, String> chunkToValue = entry.getValue();
            if (chunkToValue.size() < 2) {
                continue;
            }
            boolean distinct = chunkToValue.values().stream().distinct().count() > 1;
            if (!distinct) {
                continue;
            }
            StringBuilder detail = new StringBuilder();
            chunkToValue.forEach((id, value) -> detail.append(id).append('=').append(value).append("; "));
            groups.add(new EvidenceAssessment.ConflictGroup(
                    entry.getKey(), List.copyOf(chunkToValue.keySet()), detail.toString().strip()));
        }
        return List.copyOf(groups);
    }

    private Map<String, String> extractParamValues(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = PARAM_VALUE_PATTERN.matcher(text);
        while (matcher.find()) {
            String topic = matcher.group(1).strip();
            String value = matcher.group(2);
            String unit = matcher.group(3) == null ? "" : matcher.group(3).strip();
            values.putIfAbsent(topic + "(" + unit + ")", value);
        }
        return values;
    }

    /** 直接支持：故障码或设备类型直接命中证据文本。 */
    private boolean hasDirectSupport(RetrievedChunk chunk, EquipmentFacts facts) {
        if (facts == null) {
            return false;
        }
        String text = lower((chunk.title() == null ? "" : chunk.title())
                + " " + (chunk.content() == null ? "" : chunk.content()));
        if (StringUtils.hasText(facts.faultCode()) && text.contains(facts.faultCode().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return StringUtils.hasText(facts.deviceType()) && text.contains(facts.deviceType());
    }

    /** 事实支持信号：故障码/设备类型命中，或症状与证据文本存在 2-gram 词面重合。 */
    private boolean hasFactSupport(RetrievedChunk chunk, EquipmentFacts facts) {
        if (hasDirectSupport(chunk, facts)) {
            return true;
        }
        if (facts == null || facts.observedSymptoms() == null) {
            return false;
        }
        String text = lower((chunk.title() == null ? "" : chunk.title())
                + " " + (chunk.content() == null ? "" : chunk.content()));
        for (String symptom : facts.observedSymptoms()) {
            if (!StringUtils.hasText(symptom)) {
                continue;
            }
            String normalized = lower(symptom.strip());
            if (normalized.length() >= 2 && text.contains(normalized)) {
                return true;
            }
            if (shareBigram(text, normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean shareBigram(String text, String symptom) {
        if (symptom.length() < 4 || text.length() < 4) {
            return false;
        }
        java.util.Set<String> bigrams = new java.util.HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        for (int i = 0; i + 2 <= symptom.length(); i++) {
            if (bigrams.contains(symptom.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    private String extractModelCode(String text) {
        Matcher matcher = MODEL_CODE_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (FAULT_CODE_SHAPE.matcher(candidate).matches()) {
                continue;
            }
            return normalizeModel(candidate);
        }
        return null;
    }

    private String normalizeModel(String model) {
        return model == null ? null : model.trim().toUpperCase(Locale.ROOT);
    }

    private DocumentLifecycleStatus parseLifecycle(String value) {
        if (value == null || value.isBlank()) {
            return null; // 历史数据缺失生命周期元数据：视为兼容（不主动过滤）
        }
        try {
            return DocumentLifecycleStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("未知生命周期状态: {}，按不参与过滤处理", value);
            return null;
        }
    }

    private EvidenceAssessment refuse(RagReasonCode reasonCode, String reason,
                                      List<String> evidenceIds, List<String> acceptedIds,
                                      List<String> rejectedIds, String sufficiency) {
        return new EvidenceAssessment(RagDecision.REFUSE, reasonCode, reason,
                evidenceIds, acceptedIds, rejectedIds, List.of(), List.of(),
                List.of(), List.of(), sufficiency);
    }

    private String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
