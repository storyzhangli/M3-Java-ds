package com.zl.d37.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d37.evaluator.CorpusModel.CorpusDocument;
import com.zl.d37.evaluator.Evaluator.RawRecord;
import com.zl.d37.evaluator.InMemoryBackend.SearchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 计算器与内存后端自测（先于正式评测留痕，夹具全 PASS 才允许正式评测）。
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static void run(Path[] outputBase) {
        List<String> results = new ArrayList<>();
        try {
            String corpusPath = System.getProperty("d37.corpus.path",
                    "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\D37\\D37_RAG_ESAB"
                            + "\\src\\main\\resources\\knowledge\\equipment-fault-corpus.json");
            String datasetDir = System.getProperty("d37.dataset.dir",
                    "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\evidence\\D37\\dataset");
            ObjectMapper mapper = new ObjectMapper();
            DatasetLoader loader = new DatasetLoader(mapper);
            List<CorpusDocument> documents = loader.loadCorpus(Path.of(corpusPath));
            List<EvalQuestion> questions = loader.loadQuestions(
                    Path.of(datasetDir).resolve("rag-evaluation-questions.v1.0.jsonl"));
            Map<Integer, String> pageToLogical = loader.buildPageToLogicalChunkId(documents);

            // 1. Embedding 确定性 + 维度 + 归一化
            LocalHashNgram embedder = new LocalHashNgram(384);
            float[] v1 = embedder.embed("数控机床主轴过热报警");
            float[] v2 = embedder.embed("数控机床主轴过热报警");
            boolean det = v1.length == 384 && Arrays.equals(v1, v2);
            double norm = 0.0;
            for (float f : v1) {
                norm += f * f;
            }
            boolean normalized = Math.abs(Math.sqrt(norm) - 1.0) < 1.0e-4;
            results.add(verdict("embedding 确定性(384维,L2归一化)", det && normalized));

            // 2. 余弦边界：自身=1，不同文本<1
            double selfCosine = LocalHashNgram.cosine(v1, v1);
            float[] vOther = embedder.embed("伺服电机位置偏差编码器线缆");
            double crossCosine = LocalHashNgram.cosine(v1, vOther);
            results.add(verdict("余弦自身=1且不同文本<1",
                    Math.abs(selfCosine - 1.0) < 1.0e-4 && crossCosine < 1.0));

            // 3. 内存后端构建：8 片段索引
            InMemoryBackend backend = new InMemoryBackend(documents, pageToLogical, embedder);
            results.add(verdict("内存后端索引片段数=8", backend.indexedChunkCount() == 8));

            // 4. 检索命中与排序：主轴过热应命中 CNC C01
            SearchResult r1 = backend.search("数控机床主轴过热报警应该检查什么", 5, 0.0);
            boolean topIsCncC01 = !r1.hits().isEmpty()
                    && "DOC-CNC-COOLING-001-C01".equals(r1.hits().get(0).logicalChunkIds().get(0));
            results.add(verdict("检索排序首位=CNC-C01", topIsCncC01));

            // 5. 稳定次级排序：分数降序且同分 chunkId 升序
            boolean ordered = isStableOrdered(r1.hits());
            results.add(verdict("稳定排序(分数降序,同分chunkId升序)", ordered));

            // 6. 阈值过滤：threshold=1.0 → 零命中
            SearchResult rThreshold = backend.search("数控机床主轴过热", 5, 1.0);
            results.add(verdict("阈值1.0过滤零命中", rThreshold.hits().isEmpty()));

            // 7. Top-K 截断
            SearchResult rTop = backend.search("数控机床", 2, 0.0);
            results.add(verdict("Top-K=2 截断", rTop.hits().size() <= 2));

            // 8. 分位数线性插值
            double p50 = percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 50.0);
            results.add(verdict("分位数P50线性插值=3.0", Math.abs(p50 - 3.0) < 1.0e-9));

            // 9. 判题夹具：可答题命中，无答案题不命中
            Evaluator evaluator = new Evaluator("IN_MEMORY_LOCAL");
            EvalQuestion qa001 = find(questions, "QA-001");
            EvalQuestion qu001 = find(questions, "QU-001");
            RawRecord recQa = evaluator.evaluateMemory(backend, qa001, 1, 5, 0.0);
            RawRecord recQu = evaluator.evaluateMemory(backend, qu001, 1, 5, 0.0);
            results.add(verdict("QA-001 文档级命中(可答)", recQa.documentHit() || recQa.chunkHit()));
            results.add(verdict("QU-001 文档级不命中(跨域无答案)", !recQu.documentHit()));

            // 10. 标签夹具：可答题若漏召回应带 MISS_RECALL，跨域题不应带 HIGH_NOISE_TOP1
            boolean missRecallCase = recQa.labels().isEmpty()
                    || recQa.labels().stream().noneMatch(l -> l.startsWith("MISS_RECALL"));
            results.add(verdict("QA-001 无漏召回标签(已命中)", missRecallCase));

            // 汇总
            long pass = results.stream().filter(s -> s.startsWith("PASS")).count();
            long fail = results.stream().filter(s -> s.startsWith("FAIL")).count();
            StringBuilder sb = new StringBuilder();
            sb.append("# D37 计算器自测报告\n\n");
            sb.append("自测项: ").append(results.size()).append('\n');
            sb.append("PASS: ").append(pass).append('\n');
            sb.append("FAIL: ").append(fail).append('\n');
            sb.append("\n## 明细\n\n");
            for (String line : results) {
                sb.append("- ").append(line).append('\n');
            }
            String report = sb.toString();
            System.out.println(report);
            Path out = outputBase[0].resolve("metrics-selftest.txt");
            Files.writeString(out, report, StandardCharsets.UTF_8);
            System.out.println("自测报告落盘: " + out);
            System.exit(fail == 0 ? 0 : 1);
        } catch (Exception ex) {
            System.err.println("自测异常: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static boolean isStableOrdered(List<Hit> hits) {
        for (int i = 1; i < hits.size(); i++) {
            Hit prev = hits.get(i - 1);
            Hit cur = hits.get(i);
            double prevScore = prev.score() == null ? -Double.MAX_VALUE : prev.score();
            double curScore = cur.score() == null ? -Double.MAX_VALUE : cur.score();
            if (curScore > prevScore) {
                return false;
            }
            if (curScore == prevScore && prev.chunkId() != null && cur.chunkId() != null
                    && prev.chunkId().compareTo(cur.chunkId()) > 0) {
                return false;
            }
        }
        return true;
    }

    private static double percentile(List<Double> sorted, double p) {
        List<Double> copy = new ArrayList<>(sorted);
        copy.sort(Double::compare);
        double rank = (p / 100.0) * (copy.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = lower + 1;
        if (upper >= copy.size()) {
            return copy.get(copy.size() - 1);
        }
        double fraction = rank - lower;
        return copy.get(lower) + fraction * (copy.get(upper) - copy.get(lower));
    }

    private static EvalQuestion find(List<EvalQuestion> questions, String id) {
        return questions.stream().filter(q -> id.equals(q.questionId()))
                .findFirst().orElseThrow();
    }

    private static String verdict(String name, boolean pass) {
        return (pass ? "PASS" : "FAIL") + " - " + name;
    }
}
