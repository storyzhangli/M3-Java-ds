package com.zl.d37.evaluator;

import com.zl.d37.evaluator.Evaluator.QuestionOutcome;
import com.zl.d37.evaluator.Evaluator.RawRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标聚合（纯函数，从指定轮次的原始记录复算，禁止手填）。
 *
 * <p>分母为 0 时对应指标标 N/A，不伪装为 0 分。延迟只报分布，不进基线对比键。</p>
 */
public final class Metrics {

    private Metrics() {
    }

    public record LatencyStats(String name, int count, double avgMs, double p50Ms,
                               double p95Ms, double minMs, double maxMs) {
    }

    public record SceneStats(String scenario,
                             int questionCount,
                             int evaluableCount,
                             int hitQuestionCount,
                             double hitRate,
                             double macroRecall,
                             double microPrecision,
                             double noiseRatio,
                             int refusalCorrectCount,
                             int refusalExpectedCount,
                             double refusalCorrectRate,
                             int falseRefusalCount,
                             int answerableCount,
                             double falseRefusalRate,
                             int falseAnswerCount,
                             int unanswerableCount,
                             double falseAnswerRate) {
    }

    public record Summary(List<SceneStats> scenes,
                          SceneStats overall,
                          List<LatencyStats> latency,
                          Map<String, Integer> labelDistribution,
                          Map<String, Integer> verdictDistribution,
                          int totalRecords) {
    }

    /**
     * 聚合指标；round 指定使用第几轮（1 起）。
     */
    public static Summary compute(List<QuestionOutcome> outcomes, int round) {
        List<RawRecord> records = new ArrayList<>();
        for (QuestionOutcome outcome : outcomes) {
            outcome.rounds().stream()
                    .filter(r -> r.round() == round)
                    .findFirst()
                    .ifPresent(records::add);
        }

        List<SceneStats> scenes = new ArrayList<>();
        for (String scenario : List.of("ANSWERABLE", "UNANSWERABLE", "TERMINOLOGY", "BOUNDARY")) {
            List<RawRecord> sceneRecords = records.stream()
                    .filter(r -> scenario.equals(r.scenario()))
                    .toList();
            if (!sceneRecords.isEmpty()) {
                scenes.add(sceneStats(scenario, sceneRecords));
            }
        }
        SceneStats overall = sceneStats("OVERALL", records);

        List<LatencyStats> latency = List.of(
                latency("queryEmbeddingMs", records.stream()
                        .map(RawRecord::queryEmbeddingMs).filter(v -> v >= 0).toList()),
                latency("retrievalElapsedMillis", records.stream()
                        .map(RawRecord::retrievalElapsedMillis).filter(v -> v >= 0).toList()),
                latency("totalElapsedMillis", records.stream()
                        .map(RawRecord::totalElapsedMillis).filter(v -> v >= 0).toList()),
                latency("inMemorySearchMs", records.stream()
                        .map(RawRecord::inMemorySearchMs).filter(v -> v >= 0).toList()));

        Map<String, Integer> labelDistribution = new LinkedHashMap<>();
        for (RawRecord record : records) {
            for (String label : record.labels()) {
                labelDistribution.merge(label, 1, Integer::sum);
            }
        }
        Map<String, Integer> verdictDistribution = new LinkedHashMap<>();
        for (RawRecord record : records) {
            verdictDistribution.merge(record.verdict(), 1, Integer::sum);
        }

        return new Summary(List.copyOf(scenes), overall, latency,
                Map.copyOf(labelDistribution), Map.copyOf(verdictDistribution),
                records.size());
    }

    private static SceneStats sceneStats(String scenario, List<RawRecord> records) {
        int questionCount = records.size();

        // HitRate 文档级：有文档标注且命中的题
        int evaluableCount = records.size();
        int hitQuestionCount = (int) records.stream().filter(RawRecord::documentHit).count();
        double hitRate = questionCount == 0 ? Double.NaN
                : (double) hitQuestionCount / questionCount;

        // Macro Recall：每题 recall 平均（有 chunk 标注用 chunk，否则用文档）
        double recallSum = 0.0;
        int recallCount = 0;
        for (RawRecord record : records) {
            double recall = singleRecall(record);
            if (!Double.isNaN(recall)) {
                recallSum += recall;
                recallCount++;
            }
        }
        double macroRecall = recallCount == 0 ? Double.NaN : recallSum / recallCount;

        // micro Precision：按标注口径（有 chunk 标注用 chunk 级，否则用文档级）
        int relevantTotal = 0;
        int returnedTotal = 0;
        for (RawRecord record : records) {
            if (!record.expectedChunkIds().isEmpty()) {
                relevantTotal += record.relevantChunkCount();
                returnedTotal += record.returnedChunkCount();
            } else if (!record.expectedDocumentIds().isEmpty()) {
                relevantTotal += record.relevantDocumentCount();
                returnedTotal += record.returnedDocumentCount();
            }
        }
        double microPrecision = returnedTotal == 0 ? Double.NaN
                : (double) relevantTotal / returnedTotal;
        double noiseRatio = Double.isNaN(microPrecision) ? Double.NaN : 1.0 - microPrecision;

        // 拒答正确率：期望受控不答且实际受控不答
        int refusalExpected = 0;
        int refusalCorrect = 0;
        for (RawRecord record : records) {
            String expected = record.expectedDecision();
            if (!"ANSWER".equals(expected)) {
                refusalExpected++;
                if (record.actualDecision() != null
                        && !"ANSWER".equals(record.actualDecision())) {
                    refusalCorrect++;
                }
            }
        }
        double refusalCorrectRate = refusalExpected == 0 ? Double.NaN
                : (double) refusalCorrect / refusalExpected;

        // 误拒率：可答（期望 ANSWER）但实际被拒
        int answerable = 0;
        int falseRefusal = 0;
        for (RawRecord record : records) {
            if ("ANSWER".equals(record.expectedDecision())) {
                answerable++;
                if (record.actualDecision() != null
                        && !"ANSWER".equals(record.actualDecision())) {
                    falseRefusal++;
                }
            }
        }
        double falseRefusalRate = answerable == 0 ? Double.NaN
                : (double) falseRefusal / answerable;

        // 误答率：无答案（期望非 ANSWER）但实际 ANSWER
        int unanswerable = 0;
        int falseAnswer = 0;
        for (RawRecord record : records) {
            if (!"ANSWER".equals(record.expectedDecision())) {
                unanswerable++;
                if ("ANSWER".equals(record.actualDecision())) {
                    falseAnswer++;
                }
            }
        }
        double falseAnswerRate = unanswerable == 0 ? Double.NaN
                : (double) falseAnswer / unanswerable;

        return new SceneStats(scenario, questionCount, evaluableCount, hitQuestionCount,
                hitRate, macroRecall, microPrecision, noiseRatio,
                refusalCorrect, refusalExpected, refusalCorrectRate,
                falseRefusal, answerable, falseRefusalRate,
                falseAnswer, unanswerable, falseAnswerRate);
    }

    private static double singleRecall(RawRecord record) {
        if (!record.expectedChunkIds().isEmpty()) {
            if (record.expectedChunkIds().isEmpty()) {
                return Double.NaN;
            }
            return (double) record.relevantChunkCount() / record.expectedChunkIds().size();
        }
        if (!record.expectedDocumentIds().isEmpty()) {
            return (double) record.relevantDocumentCount() / record.expectedDocumentIds().size();
        }
        return Double.NaN;
    }

    private static LatencyStats latency(String name, List<Long> values) {
        if (values.isEmpty()) {
            return new LatencyStats(name, 0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        return new LatencyStats(name, sorted.size(),
                (double) sum / sorted.size(),
                percentile(sorted, 50.0),
                percentile(sorted, 95.0),
                sorted.get(0),
                sorted.get(sorted.size() - 1));
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = lower + 1;
        if (upper >= sorted.size()) {
            return sorted.get(sorted.size() - 1);
        }
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
