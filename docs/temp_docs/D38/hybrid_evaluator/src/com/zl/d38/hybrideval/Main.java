package com.zl.d38.hybrideval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D38 R02 混合检索评测入口。
 *
 * <p>调度：固定题序单线程，同一 run 内 ABAB 策略交错（题 × 轮 × 策略 × run），
 * 抵消时段漂移；预热不计入统计。质量投影逐 run 落盘并跨 run 逐字节 diff，
 * 不一致 → BASELINE_DRIFT 熔断（exit 3）。
 * hybrid 端点不可用（enabled=false 或 404）→ exit 2，禁止静默只跑对照组。</p>
 *
 * <p>退出码：0 成功；1 参数/IO 错误；2 环境不可用；3 投影一致性熔断。</p>
 */
public final class Main {

    private static final String FROZEN_DATASET_SHA256 =
            "896bceaa1ede76dc40577a8b97473a532ba1984414ab0e52d61c0fffa5234bec";
    private static final String FROZEN_CORPUS_SHA256 =
            "aad6ac4df5585aad5078d1c94df4e5093af15a9730e230149cfde0940335ab0b";

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if ("selftest".equals(opt.getOrDefault("mode", "evaluate"))) {
            SelfTest.run(Path.of(required(opt, "outputBase")).resolve("metrics-selftest.txt"));
            return;
        }

        String baseUrl = required(opt, "baseUrl").replaceAll("/+$", "");
        Path datasetDir = Path.of(required(opt, "datasetDir"));
        Path corpusPath = Path.of(required(opt, "corpusPath"));
        Path outputBase = Path.of(required(opt, "outputBase"));
        int runs = Integer.parseInt(opt.getOrDefault("runs", "2"));
        int rounds = Integer.parseInt(opt.getOrDefault("rounds", "3"));
        int warmup = Integer.parseInt(opt.getOrDefault("warmup", "3"));
        int topK = Integer.parseInt(opt.getOrDefault("topK", "5"));

        ObjectMapper mapper = new ObjectMapper();
        DatasetLoader loader = new DatasetLoader(mapper);

        String datasetSha = loader.sha256(datasetDir.resolve("rag-evaluation-questions.v1.0.jsonl"));
        if (!FROZEN_DATASET_SHA256.equalsIgnoreCase(datasetSha)) {
            System.err.println("冻结数据集 SHA-256 不匹配: " + datasetSha);
            System.exit(2);
        }
        String corpusSha = loader.sha256(corpusPath);
        if (!FROZEN_CORPUS_SHA256.equalsIgnoreCase(corpusSha)) {
            System.err.println("冻结语料 SHA-256 不匹配: " + corpusSha);
            System.exit(2);
        }

        List<EvalQuestion> questions = loader.loadQuestions(
                datasetDir.resolve("rag-evaluation-questions.v1.0.jsonl"));
        if (questions.size() != 20) {
            System.err.println("冻结问题集应为 20 题，实际 " + questions.size());
            System.exit(2);
        }
        Map<Integer, String> pageToLogical =
                loader.buildPageToLogicalChunkId(loader.loadCorpus(corpusPath));

        StrategyBackend backend = new StrategyBackend(baseUrl, mapper, pageToLogical);

        // 环境探测：端口从 baseUrl 解析（修复 D37 硬编码 8080 缺陷）
        URI uri = URI.create(baseUrl);
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        boolean tcp = tcpReachable(uri.getHost(), port);
        if (!tcp) {
            System.err.println("目标实例不可达: " + uri.getHost() + ":" + port);
            System.exit(2);
        }
        if (!backend.hybridAvailable()) {
            System.err.println("hybrid 端点不可用（app.hybrid.enabled 可能为 false）"
                    + "，实验熔断，禁止静默只跑对照组");
            System.exit(2);
        }

        List<String> strategies = List.of(StrategyBackend.PURE_VECTOR, StrategyBackend.HYBRID_RRF);

        // 预热：每策略 warmup 次（不计入统计、不落原始记录）
        for (String strategy : strategies) {
            for (int i = 0; i < warmup; i++) {
                backend.search(questions.get(0).question(), topK, strategy);
            }
        }
        System.out.println("预热完成: 每策略 " + warmup + " 次，不计入统计");

        Map<String, Object> envProbe = new LinkedHashMap<>();
        envProbe.put("baseUrl", baseUrl);
        envProbe.put("port", port);
        envProbe.put("datasetSha256", datasetSha);
        envProbe.put("corpusSha256", corpusSha);
        envProbe.put("hybridAvailable", true);
        envProbe.put("aiMode", System.getenv().getOrDefault("AI_MODE", "mock"));

        ResultWriter writer = new ResultWriter(mapper);
        Map<String, List<String>> projectionByStrategyRun = new LinkedHashMap<>();
        int totalRecords = 0;

        for (int run = 1; run <= runs; run++) {
            Map<String, List<Evaluator.RawRecord>> byStrategy = new LinkedHashMap<>();
            for (String strategy : strategies) {
                byStrategy.put(strategy, new ArrayList<>());
            }
            for (int round = 1; round <= rounds; round++) {
                for (EvalQuestion question : questions) {
                    // 同 run ABAB 交错：每题先对照后实验（run2 亦同序，交错的是轮维度，
                    // 两策略时间片均匀铺开）
                    for (String strategy : strategies) {
                        Evaluator evaluator = new Evaluator();
                        Evaluator.RawRecord record =
                                evaluator.evaluate(backend, question, round, topK, strategy);
                        byStrategy.get(strategy).add(record);
                        totalRecords++;
                    }
                }
            }
            for (String strategy : strategies) {
                List<Evaluator.RawRecord> records = byStrategy.get(strategy);
                Path runDir = outputBase.resolve("run" + run).resolve(strategy);
                Metrics.Summary summary = Metrics.compute(records);
                writer.writeRun(runDir, records, summary, envProbe, warmup, rounds,
                        "v1.0", strategy);
                projectionByStrategyRun
                        .computeIfAbsent(strategy, k -> new ArrayList<>())
                        .add(runDir.resolve("projection.txt").toString());
                System.out.println("run" + run + " " + strategy + " 落盘: "
                        + records.size() + " 条 -> " + runDir);
            }
        }

        int expected = 20 * rounds * runs * strategies.size();
        if (totalRecords != expected) {
            System.err.println("记录数不符: 实际 " + totalRecords + " 期望 " + expected);
            System.exit(1);
        }

        // 跨 run 质量投影逐字节一致性门禁
        boolean drift = false;
        Path consistencyDir = outputBase.resolve("consistency");
        Files.createDirectories(consistencyDir);
        for (String strategy : strategies) {
            List<String> files = projectionByStrategyRun.get(strategy);
            StringBuilder report = new StringBuilder();
            byte[] first = Files.readAllBytes(Path.of(files.get(0)));
            boolean same = true;
            for (int i = 1; i < files.size(); i++) {
                byte[] other = Files.readAllBytes(Path.of(files.get(i)));
                if (!java.util.Arrays.equals(first, other)) {
                    same = false;
                    report.append("DIFF: run1 与 run").append(i + 1)
                            .append(" 投影不一致\n");
                }
            }
            report.insert(0, "strategy=" + strategy + " "
                    + (same ? "CONSISTENCY_PASS" : "CONSISTENCY_FAIL") + '\n');
            Files.writeString(consistencyDir.resolve("consistency-" + strategy + ".txt"),
                    report.toString(), StandardCharsets.UTF_8);
            System.out.println(report.toString().trim());
            drift |= !same;
        }
        if (drift) {
            System.err.println("BASELINE_DRIFT：跨 run 质量投影不一致，熔断于报告前");
            System.exit(3);
        }
        System.out.println("评测完成: 总记录 " + totalRecords + "，全部一致性门禁 PASS");
    }

    private static boolean tcpReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-") && i + 1 < args.length) {
                map.put(args[i].substring(1), args[++i]);
            }
        }
        return map;
    }

    private static String required(Map<String, String> opt, String key) {
        String value = opt.get(key);
        if (value == null || value.isBlank()) {
            System.err.println("缺少必需参数 -" + key);
            System.exit(1);
        }
        return value;
    }
}
