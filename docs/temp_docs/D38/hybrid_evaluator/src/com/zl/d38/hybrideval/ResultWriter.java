package com.zl.d38.hybrideval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d38.hybrideval.Evaluator.RawRecord;
import com.zl.d38.hybrideval.Metrics.LatencyStats;
import com.zl.d38.hybrideval.Metrics.SceneStats;
import com.zl.d38.hybrideval.Metrics.Summary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 证据落盘器（改造自 D37 ResultWriter：CSV 增加 strategy/融合观测列，
 * summary 增加策略与一致性段；原始记录逐轮全保真）。
 */
public final class ResultWriter {

    private final ObjectMapper objectMapper;

    public ResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeRun(Path runDir, List<RawRecord> records, Summary summary,
                         Map<String, Object> envProbe, int warmupRuns, int rounds,
                         String datasetVersion, String strategy) throws IOException {
        Files.createDirectories(runDir);

        StringBuilder jsonl = new StringBuilder();
        StringBuilder projections = new StringBuilder();
        for (RawRecord record : records) {
            jsonl.append(objectMapper.writeValueAsString(record)).append('\n');
            projections.append(record.qualityProjection()).append('\n');
        }
        Files.writeString(runDir.resolve("raw-results.jsonl"),
                jsonl.toString(), StandardCharsets.UTF_8);

        writeCsv(runDir.resolve("baseline.csv"), records);
        Files.writeString(runDir.resolve("projection.txt"),
                projections.toString(), StandardCharsets.UTF_8);
        writeSummary(runDir.resolve("summary.md"), strategy, summary, envProbe,
                warmupRuns, rounds, datasetVersion, records.size());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(runDir.resolve("env-probe.json").toFile(), envProbe);
    }

    private void writeCsv(Path path, List<RawRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("questionId,scenario,round,strategy,hitCount,returnedDocumentCount,")
                .append("returnedChunkCount,relevantDocumentCount,relevantChunkCount,noiseCount,")
                .append("documentHit,chunkHit,expectedDecision,actualDecision,actualStatus,")
                .append("reasonCode,labels,verdict,queryEmbeddingMs,retrievalElapsedMillis,")
                .append("totalElapsedMillis,keywordSearchMs,fusionMs,hitDocumentIds,")
                .append("hitLogicalChunkIds,hitChunkIds,fusionTraceDigest,requestError,requestTimeout\n");
        for (RawRecord r : records) {
            sb.append(csv(r.questionId())).append(',')
                    .append(csv(r.scenario())).append(',')
                    .append(r.round()).append(',')
                    .append(csv(r.strategy())).append(',')
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
                    .append(r.keywordSearchMs()).append(',')
                    .append(r.fusionMs()).append(',')
                    .append(csv(String.join(";", r.hitDocumentIds()))).append(',')
                    .append(csv(String.join(";", r.hitLogicalChunkIds()))).append(',')
                    .append(csv(String.join(";", r.hitChunkIds()))).append(',')
                    .append(csv(String.join(";", r.fusionTraceDigest()))).append(',')
                    .append(r.requestError()).append(',')
                    .append(r.requestTimeout()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary(Path path, String strategy, Summary summary,
                              Map<String, Object> envProbe, int warmupRuns, int rounds,
                              String datasetVersion, int totalRecords) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# D38 R02 混合检索评测汇总（策略 ").append(strategy).append("）\n\n");
        sb.append("- 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("- 数据集版本: ").append(datasetVersion).append('\n');
        sb.append("- 预热轮次: ").append(warmupRuns).append("（不计入统计）\n");
        sb.append("- 正式轮次: ").append(rounds).append('\n');
        sb.append("- 原始记录条数: ").append(totalRecords).append('\n');
        sb.append("- 请求错误数: ").append(summary.requestErrorCount()).append('\n');
        sb.append("- 请求超时数: ").append(summary.requestTimeoutCount()).append('\n');
        for (Map.Entry<String, Object> entry : envProbe.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
                    .append(entry.getValue()).append('\n');
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
        sb.append("\n## 失败标签分布\n\n");
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
}
