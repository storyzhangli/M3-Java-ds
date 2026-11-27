package com.zl.d44.rerank.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.experiment.ExperimentQuestion;
import com.zl.d44.rerank.LocalLexicalReranker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R0–R3 真实实验载体（非默认 surefire 匹配；实验轮显式触发）：
 * mvn -o -B -f P\P_RERANK\pom.xml test "-Dsurefire.spring.profile=experiment" "-Dtest=RerankValueExperimentIT"
 * 且环境变量 P_EXP_RUN=true，否则整类 Assumptions 跳过（NOT RUN 如实登记）。
 */
@SpringBootTest
class RerankValueExperimentIT {

    private static final String QUESTIONS_REL =
            "docs/evidence/D25/rag-design/questions.jsonl";

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("R0–R2 真实执行 + R3 BLOCKED + 4-JSON 落盘（H6 mock 零外呼）")
    void runFrozenMatrix() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("P_EXP_RUN")),
                "P_EXP_RUN!=true：实验轮 NOT RUN");
        Optional<RerankValueExperimentRunner> runnerBean =
                Optional.ofNullable(context.getBeanProvider(RerankValueExperimentRunner.class)
                        .getIfAvailable());
        Assumptions.assumeTrue(runnerBean.isPresent(),
                "pgvector 未启用：实验轮 NOT RUN");

        List<ExperimentQuestion> questions = loadQuestions();
        assertEquals(10, questions.size(), "冻结问题集必须为 Q01–Q10");
        LocalLexicalReranker localBean = context.getBean(LocalLexicalReranker.class);
        assertNotNull(localBean, "experiment profile 应装配本地端口");

        RerankValueExperimentRunner.Outcome outcome = runnerBean.get().run(questions, 3);

        RerankGroupSummary r0 = summaryOf(outcome, "R0");
        RerankGroupSummary r3 = summaryOf(outcome, "R3");
        assertEquals("EXECUTED", r0.status());
        assertEquals("BLOCKED", r3.status());
        assertEquals("RERANK_REMOTE_DISABLED", r3.blockedReason());
        for (String g : List.of("R0", "R1", "R2")) {
            RerankGroupSummary s = summaryOf(outcome, g);
            assertEquals(30, s.runCount(), g + " 应为 10 题×3 重复");
            assertEquals(0, s.failCount(), g + " G-B：FAIL 必须为 0");
            assertTrue(s.repeatDivergenceFree(), g + " G-E：repeat Top5 必须零分歧");
        }
        assertEquals(90, outcome.runs().size(), "mock 轮仅 R0–R2 产生运行明细");
        assertTrue(outcome.runs().stream().noneMatch(r -> "R3".equals(r.group())));
        assertTrue(outcome.runs().stream()
                .allMatch(r -> r.rerankTotalTokens() == null), "mock 轮零计费");

        List<String> decided = outcome.decisions().stream()
                .map(RerankDecisionRecord::group).toList();
        assertTrue(decided.containsAll(List.of("R1", "R2", "R3")));
        for (RerankDecisionRecord d : outcome.decisions()) {
            assertTrue(List.of("ADOPTED_OPTIONAL", "NOT_ADOPTED",
                            "NOT_ADOPTED_PENDING_COST", "BLOCKED").contains(d.verdict()),
                    "决策枚举越界: " + d.verdict());
        }

        Path evidenceDir = resolveEvidenceDir();
        new RerankExperimentResultWriter(new ObjectMapper()).write(
                evidenceDir,
                List.of(RerankExperimentGroup.values()),
                outcome.runs(), outcome.summaries(), outcome.decisions(),
                withMapping(outcome));
        for (String f : List.of(RerankExperimentResultWriter.MATRIX_FILE,
                RerankExperimentResultWriter.RESULTS_FILE,
                RerankExperimentResultWriter.SUMMARY_FILE,
                RerankExperimentResultWriter.DECISION_FILE)) {
            assertTrue(Files.size(evidenceDir.resolve(f)) > 2, "落盘缺失: " + f);
        }
        assertFalse(Files.exists(Path.of("docs/temp_docs/P/rerank-ledger.jsonl"))
                && Files.size(Path.of("docs/temp_docs/P/rerank-ledger.jsonl")) > 0,
                "H6：mock 轮远程台账必须零行");
    }

    private static java.util.Map<String, Object> withMapping(
            RerankValueExperimentRunner.Outcome outcome) {
        java.util.Map<String, Object> info = new java.util.LinkedHashMap<>(outcome.indexInfo());
        info.put("chunkIdMapping", outcome.chunkIdMapping());
        return info;
    }

    private static RerankGroupSummary summaryOf(RerankValueExperimentRunner.Outcome outcome,
                                                String group) {
        return outcome.summaries().stream()
                .filter(s -> s.group().equals(group)).findFirst().orElseThrow();
    }

    private static List<ExperimentQuestion> loadQuestions() throws Exception {
        Path repo = resolveRepoFile(QUESTIONS_REL);
        try (InputStream in = Files.newInputStream(repo)) {
            return ExperimentQuestion.load(in, 10, new ObjectMapper());
        }
    }

    /** surefire 工作目录为模块目录（D44/D44_RERANK），回退探测仓库根。 */
    private static Path resolveRepoFile(String rel) {
        List<Path> candidates = List.of(
                Path.of(rel),
                Path.of("../../" + rel),
                Path.of("E:/AI/project/AI-Learning-two-lp/M3-Java-ds/" + rel));
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("questions.jsonl 未找到: " + rel);
    }

    private static Path resolveEvidenceDir() {
        Path base = resolveRepoFile("docs/evidence/D44").resolve("extract");
        return base;
    }
}