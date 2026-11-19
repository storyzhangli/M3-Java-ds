package com.zl.d36.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索参数对照实验真实链路集成测试。
 *
 * <p>类级 {@link EnabledIfEnvironmentVariable} 守卫：仅当 {@code D36_EXP_RUN=true} 时才启用，
 * 默认环境下整个测试类被 JUnit 禁用（不加载上下文、不判定失败）。这是必要的，因为 test profile
 * 关闭 pgvector 后，完整 {@code @SpringBootTest} 上下文无法启动（生产 RagRetrievalService
 * 强依赖被条件关闭的 SimilarityRetrievalService）。</p>
 *
 * <p>真实执行需同时满足：{@code D36_EXP_RUN=true} 与
 * {@code -Dsurefire.spring.profile=experiment}（启用 pgvector 装配）。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "D36_EXP_RUN", matches = "true")
class RetrievalExperimentRunnerIT {

    @Autowired(required = false)
    private RetrievalExperimentRunner runner;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runFiveGroupControlledExperiment() throws Exception {
        assertThat(runner).as("experiment profile 应装配实验运行器").isNotNull();

        Path repoRoot = locateRepoRoot();
        Path questionsPath = repoRoot.resolve("docs/evidence/D25/rag-design/questions.jsonl");
        Path corpusPath = repoRoot.resolve(
                "D36/D36_SPCE/src/main/resources/knowledge/equipment-fault-corpus.json");
        Path evidenceDir = repoRoot.resolve("docs/evidence/D36");

        List<ExperimentQuestion> questions;
        try (InputStream input = Files.newInputStream(questionsPath)) {
            questions = ExperimentQuestion.load(input, 10, objectMapper);
        }
        assertThat(questions).hasSize(10);
        assertThat(questions).allSatisfy(question -> {
            assertThat(question.retrieveEnabled()).isTrue();
            assertThat(question.expectedChunkIds()).isNotEmpty();
        });

        RetrievalExperimentMatrix matrix = RetrievalExperimentMatrix.defaultMatrix();
        ExperimentRunResult result = runner.run(matrix, questions);

        assertThat(result.groups()).hasSize(5);
        for (ExperimentRunResult.GroupRunResult group : result.groups()) {
            assertThat(group.mapping().entries()).isNotEmpty();
            assertThat(group.questionRecords()).hasSize(10);
            // 隔离：所有命中 documentId 必须带本组前缀。
            String prefix = ExperimentGroup.valueOf(group.summary().groupId()).documentIdPrefix();
            for (QuestionExperimentRecord question : group.questionRecords()) {
                for (QuestionRunRecord run : question.runs()) {
                    for (ReturnedHitRecord hit : run.hits()) {
                        assertThat(hit.documentId()).startsWith(prefix);
                    }
                }
            }
        }

        ExperimentResultWriter writer = new ExperimentResultWriter(objectMapper);
        writer.write(evidenceDir, result, sha256(questionsPath), sha256(corpusPath));

        assertThat(Files.exists(evidenceDir.resolve(ExperimentResultWriter.MATRIX_FILE))).isTrue();
        assertThat(Files.exists(evidenceDir.resolve(ExperimentResultWriter.MAPPING_FILE))).isTrue();
        assertThat(Files.exists(evidenceDir.resolve(ExperimentResultWriter.RESULTS_FILE))).isTrue();
        assertThat(Files.exists(evidenceDir.resolve(ExperimentResultWriter.SUMMARY_FILE))).isTrue();
    }

    private static Path locateRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            if (Files.exists(current.resolve("docs/evidence/D25/rag-design/questions.jsonl"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法从 " + Paths.get("").toAbsolutePath()
                + " 向上定位仓库根（缺少 questions.jsonl）");
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
