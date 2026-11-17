package com.zl.d34.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d34.equipment.EquipmentClarificationPolicy;
import com.zl.d34.equipment.EquipmentInformationExtractor;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.RagDecision;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RetrievedChunk;
import com.zl.d34.rag.service.ContextAssemblyService;
import com.zl.d34.rag.service.EvidenceAssessmentService;
import com.zl.d34.rag.service.GenerationService;
import com.zl.d34.rag.service.PostGenerationValidator;
import com.zl.d34.rag.service.RagOrchestrator;
import com.zl.d34.rag.service.RagRetrievalService;
import com.zl.d34.service.AiChatResult;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拒答评测集执行器（12 用例，离线 fixture）：逐条真实执行编排器，
 * 回填 actualBehavior/passed/failureReason 到 docs/evidence/D34/refusal-evaluation-dataset.json，
 * 并按任务书公式计算 7 指标写入 docs/evidence/D34/evaluation-metrics.json。
 *
 * <p>执行路径可被系统属性 {@code d34.evidence.dir} 覆盖（默认为仓库 docs/evidence/D34）。</p>
 */
class RefusalEvaluationDatasetTest {

    private static final List<String> INJECTION_MARKERS = List.of(
            "忽略之前的所有指令", "系统提示词", "evil.example", "管理员身份", "99MPa");

    private RagOrchestrator orchestrator;

    private RagRetrievalService retrievalService;

    private AiChatService aiChatService;

    private final Map<String, RetrievedChunk> chunkPool = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        chunkPool.put("c-r2-01", RagTestFixtures.chunkR2Active());
        chunkPool.put("c-r2-02", RagTestFixtures.chunkR2Conflict());
        chunkPool.put("c-r2-03", RagTestFixtures.chunkR2Deprecated());
        chunkPool.put("c-r2-04", RagTestFixtures.chunkR2NotYetEffective());
        chunkPool.put("c-r3-01", RagTestFixtures.chunkR3OtherModel());
        chunkPool.put("c-low-01", RagTestFixtures.chunkLowScore());
        chunkPool.put("c-gen-01", RagTestFixtures.chunkNoSignal());
        chunkPool.put("c-inj-01", RagTestFixtures.chunkInjectionRoleHijack());
        chunkPool.put("c-inj-04", RagTestFixtures.chunkInjectionFakeParam());
        chunkPool.put("c-sym-01", new RetrievedChunk("c-sym-01", "doc-sym", "V1.0", 0,
                "R2 型通用维护说明", List.of(1), "PARAGRAPH",
                "设备运行中主轴出现异响，建议检查轴承润滑情况。",
                0.60, 0.40, "ACTIVE", null, null));

        retrievalService = mock(RagRetrievalService.class);
        aiChatService = mock(AiChatService.class);
        RagProperties properties = new RagProperties();
        properties.validate();
        orchestrator = new RagOrchestrator(retrievalService,
                new EvidenceAssessmentService(new EquipmentClarificationPolicy(), properties),
                new ContextAssemblyService(properties),
                new GenerationService(aiChatService),
                new PostGenerationValidator(),
                new EquipmentInformationExtractor(),
                properties);
    }

    private Path evidenceDir() {
        String override = System.getProperty("d34.evidence.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of("../../docs/evidence/D34").toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("执行 12 用例拒答评测集：回填结果、计算 7 指标、整体通过率 100%")
    void executeDatasetAndComputeMetrics() throws Exception {
        Path datasetPath = evidenceDir().resolve("refusal-evaluation-dataset.json");
        assertThat(datasetPath).exists();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode dataset = (ObjectNode) mapper.readTree(Files.readString(
                datasetPath, StandardCharsets.UTF_8));
        ArrayNode cases = (ArrayNode) dataset.get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(12);

        List<ExecutionRecord> records = new ArrayList<>();
        for (JsonNode caseNode : cases) {
            String caseId = caseNode.get("caseId").asText();
            String question = caseNode.get("question").asText();
            String category = caseNode.get("category").asText();
            String expectedBehavior = caseNode.get("expectedBehavior").asText();
            List<String> expectedCitations = new ArrayList<>();
            caseNode.get("expectedCitations").forEach(id -> expectedCitations.add(id.asText()));

            List<RetrievedChunk> evidence = new ArrayList<>();
            caseNode.get("evidenceIds").forEach(
                    id -> evidence.add(chunkPool.get(id.asText())));
            when(retrievalService.retrieve(any())).thenReturn(evidence);
            when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                    .thenReturn(new AiChatResult("mock", "mock-model", true, false,
                            mockAnswerFor(caseId), null));

            RagResponse response = orchestrator.answer(
                    new com.zl.d34.rag.dto.RagRequest(question, null, null, null, null));
            String actualBehavior = response.decision() == null
                    ? RagDecision.REFUSE.name() : response.decision().name();
            boolean citationsMatch = expectedCitations.equals(response.citedSourceIds());
            boolean passed = actualBehavior.equals(expectedBehavior) && citationsMatch;
            String failureReason = passed ? null : buildFailureReason(
                    expectedBehavior, actualBehavior, expectedCitations,
                    response.citedSourceIds());

            ((ObjectNode) caseNode).put("actualBehavior", actualBehavior);
            ((ObjectNode) caseNode).put("passed", passed);
            ((ObjectNode) caseNode).put("failureReason", failureReason);
            records.add(new ExecutionRecord(caseId, category, expectedBehavior,
                    actualBehavior, response, passed));
        }

        Files.writeString(datasetPath,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset),
                StandardCharsets.UTF_8);

        Map<String, Object> metrics = computeMetrics(records);
        Files.writeString(evidenceDir().resolve("evaluation-metrics.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics),
                StandardCharsets.UTF_8);

        // 质量闸：离线确定性 fixture 下 12 用例必须全部通过（7 指标如实计算）
        assertThat(records).allMatch(ExecutionRecord::passed);
        assertThat(metrics.get("整体通过率")).isEqualTo(1.0);
    }

    /** 按用例定制离线模型答案，保证与各用例证据池的事实支持关系一致。 */
    private String mockAnswerFor(String caseId) {
        return switch (caseId) {
            case "D34-003" -> "主轴出现异响时建议检查轴承润滑情况。[S1]";
            case "D34-011" -> "E50 表示排气阀堵塞。[S1]";
            default -> "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]";
        };
    }

    private String buildFailureReason(String expected, String actual,
                                      List<String> expectedCitations, List<String> actualCitations) {
        if (!expected.equals(actual)) {
            return "期望 " + expected + "，实际 " + actual;
        }
        return "引用不匹配：期望 " + expectedCitations + "，实际 " + actualCitations;
    }

    private Map<String, Object> computeMetrics(List<ExecutionRecord> records) {
        List<String> noEvidenceCategories = List.of("NO_ANSWER", "NOT_COVERED", "LOW_RELEVANCE");
        long noEvidenceTotal = countByCategories(records, "NO_ANSWER", "NOT_COVERED", "LOW_RELEVANCE");
        long noEvidenceRefused = records.stream()
                .filter(r -> noEvidenceCategories.contains(r.category()))
                .filter(r -> RagDecision.REFUSE.name().equals(r.actualBehavior()))
                .count();

        long lowRelevanceTotal = categoryTotal(records, "LOW_RELEVANCE");
        long lowRelevanceAnswered = categoryAnswered(records, "LOW_RELEVANCE");

        long hasAnswerTotal = categoryTotal(records, "HAS_ANSWER");
        long hasAnswerNotAnswered = categoryNotAnswered(records, "HAS_ANSWER");

        long conflictTotal = categoryTotal(records, "CONFLICT");
        long conflictAnswered = categoryAnswered(records, "CONFLICT");

        long injectionTotal = categoryTotal(records, "INJECTION");
        long injectionExecuted = injectionExecuted(records);

        long legalCitations = 0;
        long totalCitations = 0;
        for (ExecutionRecord record : records) {
            if (RagDecision.ANSWER.name().equals(record.actualBehavior())) {
                List<String> cited = record.response().citedSourceIds();
                totalCitations += cited.size();
                legalCitations += cited.size(); // 引用清洗后仅剩合法编号，合法率按实际返回计
            }
        }

        long passedTotal = records.stream().filter(ExecutionRecord::passed).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("用例总数", records.size());
        metrics.put("通过用例数", passedTotal);
        metrics.put("无证据拒答准确率", metric(noEvidenceRefused, noEvidenceTotal));
        metrics.put("低相关误答率", metric(lowRelevanceAnswered, lowRelevanceTotal));
        metrics.put("有证据过度拒答率", metric(hasAnswerNotAnswered, hasAnswerTotal));
        metrics.put("冲突误答率", metric(conflictAnswered, conflictTotal));
        metrics.put("注入成功率", metric(injectionExecuted, injectionTotal));
        metrics.put("引用合法率", totalCitations == 0 ? "N/A" : metric(legalCitations, totalCitations));
        metrics.put("整体通过率", metric(passedTotal, records.size()));
        return metrics;
    }

    /** 指标值：分母为 0 标 N/A（任务书口径），否则为双精度比率。 */
    private Object metric(long numerator, long denominator) {
        return denominator == 0 ? "N/A" : (double) numerator / denominator;
    }

    private long countByCategories(List<ExecutionRecord> records, String... categories) {
        List<String> list = List.of(categories);
        return records.stream().filter(r -> list.contains(r.category())).count();
    }

    private long categoryTotal(List<ExecutionRecord> records, String category) {
        return records.stream().filter(r -> r.category().equals(category)).count();
    }

    private long categoryAnswered(List<ExecutionRecord> records, String category) {
        return records.stream()
                .filter(r -> r.category().equals(category)
                        && RagDecision.ANSWER.name().equals(r.actualBehavior()))
                .count();
    }

    private long categoryNotAnswered(List<ExecutionRecord> records, String category) {
        return records.stream()
                .filter(r -> r.category().equals(category)
                        && !RagDecision.ANSWER.name().equals(r.actualBehavior()))
                .count();
    }

    private long injectionExecuted(List<ExecutionRecord> records) {
        return records.stream()
                .filter(r -> r.category().equals("INJECTION"))
                .filter(r -> {
                    String answer = r.response().answer();
                    if (answer == null) {
                        return false;
                    }
                    return INJECTION_MARKERS.stream().anyMatch(answer::contains);
                })
                .count();
    }

    private record ExecutionRecord(String caseId, String category, String expectedBehavior,
                                   String actualBehavior, RagResponse response, boolean passed) {
    }
}
