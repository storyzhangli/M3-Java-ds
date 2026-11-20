package com.zl.d37.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d37.evaluator.Evaluator.QuestionOutcome;
import com.zl.d37.evaluator.Evaluator.RawRecord;
import com.zl.d37.evaluator.Metrics.LatencyStats;
import com.zl.d37.evaluator.Metrics.SceneStats;
import com.zl.d37.evaluator.Metrics.Summary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 证据落盘器：raw-results.jsonl、baseline.csv、summary.md、env-probe.json。
 */
public final class ResultWriter {

    private final ObjectMapper objectMapper;

    public ResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path runDir, List<QuestionOutcome> outcomes, Summary summary,
                      EnvProbe envProbe, int warmupRuns, int rounds,
                      String datasetVersion) throws IOException {
        Files.createDirectories(runDir);

        List<RawRecord> allRecords = outcomes.stream()
                .flatMap(o -> o.rounds().stream())
                .toList();

        writeJsonl(runDir.resolve("raw-results.jsonl"), allRecords);
        writeCsv(runDir.resolve("baseline.csv"), allRecords);
        writeSummary(runDir.resolve("summary.md"), summary, envProbe, warmupRuns, rounds,
                datasetVersion, allRecords.size());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(runDir.resolve("env-probe.json").toFile(), envProbe);
    }

    private void writeJsonl(Path path, List<RawRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (RawRecord record : records) {
            sb.append(objectMapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeCsv(Path path, List<RawRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("questionId,scenario,round,retrievalBackend,hitCount,returnedDocumentCount,")
                .append("returnedChunkCount,relevantDocumentCount,relevantChunkCount,noiseCount,")
                .append("documentHit,chunkHit,expectedDecision,actualDecision,actualStatus,")
                .append("reasonCode,labels,verdict,queryEmbeddingMs,retrievalElapsedMillis,")
                .append("totalElapsedMillis,inMemorySearchMs,hitDocumentIds,hitLogicalChunkIds\n");
        for (RawRecord r : records) {
            sb.append(csv(r.questionId())).append(',')
                    .append(csv(r.scenario())).append(',')
                    .append(r.round()).append(',')
                    .append(csv(r.retrievalBackend())).append(',')
                    .append(r.hitCount()).append(',')
                    .append(r.returnedDocumentCount()).append(',')
                    .append(r.returnedChunkCount()).append(',')
                    .append(r.relevantDocumentCount()).append(',')
                    .append(r.relevantChunkCount()).append(',')
                    .append(r.noiseCount()).append(',')
                    .append(r.documentHit()).append(',')
                    .append(r.chunkHit()).append(',')
                    .append(csv(r.expectedDecision())).append(',')
                    .append(csv(r.actualDecision())).append(',')
                    .append(csv(r.actualStatus())).append(',')
                    .append(csv(r.reasonCode())).append(',')
                    .append(csv(String.join(";", r.labels()))).append(',')
                    .append(csv(r.verdict())).append(',')
                    .append(r.queryEmbeddingMs()).append(',')
                    .append(r.retrievalElapsedMillis()).append(',')
                    .append(r.totalElapsedMillis()).append(',')
                    .append(r.inMemorySearchMs()).append(',')
                    .append(csv(String.join(";", r.hitDocumentIds()))).append(',')
                    .append(csv(String.join(";", r.hitLogicalChunkIds()))).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary(Path path, Summary summary, EnvProbe envProbe,
                              int warmupRuns, int rounds, String datasetVersion,
                              int totalRecords) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# D37 RAG 评测汇总报告\n\n");
        sb.append("- 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("- 数据集版本: ").append(datasetVersion).append('\n');
        sb.append("- 预热轮次: ").append(warmupRuns).append("（不计入统计）\n");
        sb.append("- 正式轮次: ").append(rounds).append('\n');
        sb.append("- 原始记录条数: ").append(totalRecords).append('\n');
        sb.append("- 对外模式: ").append(envProbe.finalMode()).append('\n');
        sb.append("- 检索后端: ").append(envProbe.retrievalBackend()).append('\n');
        if (envProbe.degradationReason() != null) {
            sb.append("- 降级原因: ").append(envProbe.degradationReason()).append('\n');
        }
        sb.append("\n## 四态分布\n\n");
        for (Map.Entry<String, Integer> entry : summary.verdictDistribution().entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        sb.append("\n## 场景指标\n\n");
        sb.append("| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (SceneStats scene : summary.scenes()) {
            sb.append('|').append(scene.scenario()).append('|')
                    .append(scene.questionCount()).append('|')
                    .append(fmt(scene.hitRate())).append('|')
                    .append(fmt(scene.macroRecall())).append('|')
                    .append(fmt(scene.microPrecision())).append('|')
                    .append(fmt(scene.noiseRatio())).append('|')
                    .append(fmt(scene.refusalCorrectRate())).append('|')
                    .append(fmt(scene.falseRefusalRate())).append('|')
                    .append(fmt(scene.falseAnswerRate())).append("|\n");
        }
        sb.append("\n## 延迟分布（毫秒，仅分布不进基线对比键）\n\n");
        sb.append("| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (LatencyStats latency : summary.latency()) {
            sb.append('|').append(latency.name()).append('|')
                    .append(latency.count()).append('|')
                    .append(fmt(latency.avgMs())).append('|')
                    .append(fmt(latency.p50Ms())).append('|')
                    .append(fmt(latency.p95Ms())).append('|')
                    .append(fmt(latency.minMs())).append('|')
                    .append(fmt(latency.maxMs())).append("|\n");
        }
        sb.append("\n## 失败标签分布（9 类一阶 + 细分原因码）\n\n");
        for (Map.Entry<String, Integer> entry : summary.labelDistribution().entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String fmt(double value) {
        if (Double.isNaN(value)) {
            return "N/A";
        }
        return String.format("%.4f", value);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public record EnvProbe(String timestamp,
                           boolean aiApiKeyPresent,
                           String aiMode,
                           boolean chatModelAvailable,
                           String fullMode,
                           boolean pgPortReachable,
                           boolean httpReachable,
                           boolean embeddingInitializable,
                           boolean corpusLoadable,
                           String finalMode,
                           String retrievalBackend,
                           String degradationReason) {
    }
}
