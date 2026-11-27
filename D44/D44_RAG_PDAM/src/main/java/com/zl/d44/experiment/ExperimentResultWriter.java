package com.zl.d44.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验证据落盘器：写出 4 个 JSON（矩阵定义、映射、逐题结果、汇总）。
 *
 * <p>矩阵定义与执行结果分离：matrix 文件只含定义与数据集指纹，不含任何回填指标。</p>
 */
public class ExperimentResultWriter {

    public static final String MATRIX_FILE = "retrieval-experiment-matrix.json";
    public static final String MAPPING_FILE = "chunk-id-mapping.json";
    public static final String RESULTS_FILE = "experiment-results.json";
    public static final String SUMMARY_FILE = "experiment-summary.json";

    private final ObjectMapper objectMapper;

    public ExperimentResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path outputDir, ExperimentRunResult result,
                      String questionsSha256, String corpusSha256) {
        try {
            Files.createDirectories(outputDir);
            writeMatrix(outputDir, result.matrix(), questionsSha256, corpusSha256);
            writeMapping(outputDir, result);
            writeResults(outputDir, result);
            writeSummary(outputDir, result);
        } catch (IOException ex) {
            throw new ExperimentException(ExperimentReasonCode.RESULT_WRITE_FAILED,
                    "实验证据写出失败: " + outputDir, ex);
        }
    }

    private void writeMatrix(Path outputDir, RetrievalExperimentMatrix matrix,
                             String questionsSha256, String corpusSha256) throws IOException {
        Map<String, Object> fingerprints = new LinkedHashMap<>();
        fingerprints.put("questionsSha256", questionsSha256);
        fingerprints.put("corpusSha256", corpusSha256);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matrix", matrix);
        payload.put("fingerprints", fingerprints);
        writeJson(outputDir.resolve(MATRIX_FILE), payload);
    }

    private void writeMapping(Path outputDir, ExperimentRunResult result) throws IOException {
        List<ChunkIdMappingRecord> mappings = result.groups().stream()
                .map(ExperimentRunResult.GroupRunResult::mapping)
                .toList();
        writeJson(outputDir.resolve(MAPPING_FILE), mappings);
    }

    private void writeResults(Path outputDir, ExperimentRunResult result) throws IOException {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ExperimentRunResult.GroupRunResult group : result.groups()) {
            Map<String, Object> groupPayload = new LinkedHashMap<>();
            groupPayload.put("groupId", group.summary().groupId());
            groupPayload.put("questions", group.questionRecords());
            groups.add(groupPayload);
        }
        writeJson(outputDir.resolve(RESULTS_FILE), groups);
    }

    private void writeSummary(Path outputDir, ExperimentRunResult result) throws IOException {
        List<GroupExperimentSummary> summaries = result.groups().stream()
                .map(ExperimentRunResult.GroupRunResult::summary)
                .toList();
        writeJson(outputDir.resolve(SUMMARY_FILE), summaries);
    }

    private void writeJson(Path path, Object payload) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }
}
