package com.zl.d44.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P4（任务书 §6.4 完整优先级规则，纯函数，无 Spring 依赖）：
 * 六阶段 bad-case 分类器。判定顺序 PERMISSION→PARSE→CHUNKING→EMBEDDING→RETRIEVAL→GENERATION
 * （e2e-params.classifierPriority 冻结），首个命中即主阶段，其余命中记 secondaryStages；
 * 全部未命中→UNCLASSIFIED + 强制人工裁决留痕（禁静默丢弃）。
 *
 * <p>阶段信号来源：BadCaseSignals 的显式布尔位（Runner 逐场景观测赋值）+ 实码映射表
 * （KP1 冻结码：AUTH_* 六码族 / PARSE 族 / 零命中码 / RagReasonCode 生成族）。
 * 手算夹具 T1–T8 见 fixture-truth.classifierFixtures（单测逐字消费）。</p>
 */
public final class BadCaseClassifier {

    /** 六阶段 + 未分类兜底（§6.4 verbatim 命名）。 */
    public enum Stage {
        PERMISSION, PARSE, CHUNKING, EMBEDDING, RETRIEVAL, GENERATION, UNCLASSIFIED
    }

    /** 判定输入信号：所有字段来自实测证据（HTTP 状态/实码/对账观测），不允许推测。 */
    public record BadCaseSignals(
            int httpStatus,
            String reasonCode,
            boolean permissionLeakage,
            boolean parseFailure,
            boolean chunkMismatch,
            boolean vectorAbnormal,
            boolean retrievalMiss,
            boolean generationDefect) {

        public static BadCaseSignals of(int httpStatus, String reasonCode) {
            return new BadCaseSignals(httpStatus, reasonCode, false, false, false, false, false, false);
        }
    }

    /** 分类输出：主阶段+次阶段+人工裁决标志（UNCLASSIFIED 强制 true）。 */
    public record Classification(Stage primaryStage, List<Stage> secondaryStages, boolean manualAdjudicationRequired) {
        public Classification {
            secondaryStages = List.copyOf(secondaryStages);
        }
    }

    /** KP1 冻结 PARSE 族实码。 */
    static final Set<String> PARSE_CODES = Set.of(
            "PARSE_FAILED", "DOCX_TABLE_NOT_STRUCTURED", "IMAGE_TABLE_NOT_PARSED");

    /** KP1 冻结 RETRIEVAL 族码（阈值清零）。 */
    static final Set<String> RETRIEVAL_CODES = Set.of("ZERO_HITS_AFTER_THRESHOLD");

    /** RagReasonCode 生成族（§6.4 第 6 条列举）：证据已检出但回答/引用层缺陷。 */
    static final Set<String> GENERATION_CODES = Set.of(
            "INVALID_CITATION", "UNSUPPORTED_CLAIM", "FABRICATED_URL",
            "CITATION_TARGET_DELETED", "GENERATION_VALIDATION_FAILED");

    /** 分类入口：按优先级链判定，纯函数可重复调用。 */
    public Classification classify(BadCaseSignals signals) {
        List<Stage> hits = new ArrayList<>();
        if (permissionHit(signals)) {
            hits.add(Stage.PERMISSION);
        }
        if (parseHit(signals)) {
            hits.add(Stage.PARSE);
        }
        if (signals.chunkMismatch()) {
            hits.add(Stage.CHUNKING);
        }
        if (signals.vectorAbnormal()) {
            hits.add(Stage.EMBEDDING);
        }
        if (signals.retrievalMiss() || (signals.reasonCode() != null
                && RETRIEVAL_CODES.contains(signals.reasonCode()))) {
            hits.add(Stage.RETRIEVAL);
        }
        if (signals.generationDefect() || (signals.reasonCode() != null
                && GENERATION_CODES.contains(signals.reasonCode()))) {
            hits.add(Stage.GENERATION);
        }
        if (hits.isEmpty()) {
            // 全部未命中：UNCLASSIFIED + 强制人工裁决（T8 口径；禁静默丢弃）
            return new Classification(Stage.UNCLASSIFIED, List.of(), true);
        }
        return new Classification(hits.get(0), hits.subList(1, hits.size()), false);
    }

    private boolean permissionHit(BadCaseSignals signals) {
        if (signals.permissionLeakage()) {
            return true;
        }
        if (signals.httpStatus() == 401 || signals.httpStatus() == 403) {
            return true;
        }
        String code = signals.reasonCode();
        // KP1 冻结：AUTH_REQUIRED/AUTH_FORMAT_INVALID/AUTH_INVALID/AUTH_DISABLED/AUTH_EXPIRED/
        // AUTH_STORE_UNAVAILABLE + AUTH_CONTEXT_MISSING/AUTH_FORBIDDEN，全部 AUTH_ 前缀。
        return code != null && code.startsWith("AUTH_");
    }

    private boolean parseHit(BadCaseSignals signals) {
        return signals.parseFailure() || (signals.reasonCode() != null
                && PARSE_CODES.contains(signals.reasonCode()));
    }
}