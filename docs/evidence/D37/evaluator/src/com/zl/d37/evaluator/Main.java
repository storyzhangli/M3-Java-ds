package com.zl.d37.evaluator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d37.evaluator.CorpusModel.CorpusDocument;
import com.zl.d37.evaluator.Evaluator.QuestionOutcome;
import com.zl.d37.evaluator.Evaluator.RawRecord;
import com.zl.d37.evaluator.Metrics.Summary;
import com.zl.d37.evaluator.ResultWriter.EnvProbe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评测器入口（独立黑盒程序，经 HTTP 调用真实端点，或走内存降级后端）。
 *
 * <p>退出码：全 PASS=0；有 FAIL=1；仅 BLOCKED/NOT RUN=2。</p>
 */
public final class Main {

    private record Args(String baseUrl, String datasetVersion, int rounds, int warmup,
                        String backend, double threshold, int topK, String datasetDir,
                        String corpusPath, String outputBase, boolean selftestOnly) {
    }

    public static void main(String[] argv) {
        Args args = parse(argv);
        ObjectMapper mapper = new ObjectMapper();
        DatasetLoader loader = new DatasetLoader(mapper);

        try {
            if (args.selftestOnly()) {
                SelfTest.run(new Path[]{Path.of(args.outputBase())});
                return;
            }

            Path datasetDir = Path.of(args.datasetDir());
            Path questionsFile = datasetDir.resolve("rag-evaluation-questions.v1.0.jsonl");
            Path corpusFile = Path.of(args.corpusPath());

            // 1. 加载数据集并校验指纹
            List<EvalQuestion> questions = loader.loadQuestions(questionsFile);
            List<CorpusDocument> documents = loader.loadCorpus(corpusFile);
            String questionsSha256 = loader.sha256(questionsFile);
            String corpusSha256 = loader.sha256(corpusFile);
            Map<Integer, String> pageToLogical = loader.buildPageToLogicalChunkId(documents);

            // 2. 环境探测
            EnvProbe probe = probe(args, mapper);

            // 3. 后端选择与实例化
            LocalHashNgram embedder = new LocalHashNgram(384);
            InMemoryBackend memoryBackend = null;
            PgvectorBackend pgvectorBackend = null;
            String effectiveBackend = probe.retrievalBackend();
            if ("PGVECTOR".equals(effectiveBackend)) {
                pgvectorBackend = new PgvectorBackend(args.baseUrl(), mapper, pageToLogical);
            } else if ("IN_MEMORY_LOCAL".equals(effectiveBackend)) {
                memoryBackend = new InMemoryBackend(documents, pageToLogical, embedder);
            }

            // 4. 预热（不计统计）
            Evaluator evaluator = new Evaluator(effectiveBackend);
            if ("IN_MEMORY_LOCAL".equals(effectiveBackend)) {
                for (int w = 0; w < args.warmup(); w++) {
                    memoryBackend.search(questions.get(0).question(), args.topK(), args.threshold());
                }
            } else if ("PGVECTOR".equals(effectiveBackend)) {
                for (int w = 0; w < args.warmup(); w++) {
                    pgvectorBackend.search(questions.get(0).question(), args.topK());
                }
            }

            // 5. 正式轮次（固定顺序、单线程串行）
            List<QuestionOutcome> outcomes = new ArrayList<>();
            for (EvalQuestion question : questions) {
                List<RawRecord> rounds = new ArrayList<>();
                for (int round = 1; round <= args.rounds(); round++) {
                    RawRecord record;
                    if ("IN_MEMORY_LOCAL".equals(effectiveBackend)) {
                        record = evaluator.evaluateMemory(
                                memoryBackend, question, round, args.topK(), args.threshold());
                    } else if ("PGVECTOR".equals(effectiveBackend)) {
                        record = evaluator.evaluatePgvector(
                                pgvectorBackend, question, round, args.topK());
                    } else {
                        record = new RawRecord(question.questionId(), question.scenario(),
                                round, effectiveBackend, 0, 0, 0, 0, 0, 0, false, false,
                                question.expectedDecision(), null, null, null, List.of(),
                                -1L, -1L, -1L, -1L, List.of(), List.of(), List.of(),
                                List.of(), List.of(), "BLOCKED");
                    }
                    rounds.add(record);
                }
                outcomes.add(new QuestionOutcome(question, List.copyOf(rounds)));
            }

            // 6. 汇总与落盘
            Summary summary = Metrics.compute(outcomes, 1);
            Path runDir = runDir(args.outputBase(), args.datasetVersion(), effectiveBackend);
            ResultWriter writer = new ResultWriter(mapper);
            writer.write(runDir, outcomes, summary, probe, args.warmup(), args.rounds(),
                    args.datasetVersion());

            // 7. 一致性 diff（质量类字段跨轮逐字节一致才写入基线）
            boolean consistent = checkConsistency(outcomes);

            // 8. 打印关键结论
            System.out.println("=== D37 评测完成 ===");
            System.out.println("后端: " + effectiveBackend);
            System.out.println("运行目录: " + runDir);
            System.out.println("四态: " + summary.verdictDistribution());
            System.out.println("质量类字段跨轮一致性: " + (consistent ? "一致" : "不一致"));
            int exitCode = exitCode(summary.verdictDistribution());
            System.out.println("退出码: " + exitCode);
            System.exit(exitCode);
        } catch (Exception ex) {
            System.err.println("评测器异常: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * 环境探测：双模式 × 检索后端三态（两维度独立判定）。
     */
    private static EnvProbe probe(Args args, ObjectMapper mapper) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        boolean aiApiKeyPresent = hasEnv("AI_API_KEY");
        String aiMode = env("AI_MODE");
        boolean chatModelAvailable = "deepseek".equalsIgnoreCase(aiMode) && aiApiKeyPresent;
        String fullMode = chatModelAvailable ? "full" : "offline";

        boolean pgReachable = tcpReachable("192.168.200.153", 5432, 2000);
        boolean httpReachable = args.baseUrl() != null && !args.baseUrl().isBlank()
                && tcpReachable(hostOf(args.baseUrl()), 8080, 2000);

        boolean embeddingInitializable = false;
        try {
            LocalHashNgram embedder = new LocalHashNgram(384);
            float[] v1 = embedder.embed("自测文本");
            float[] v2 = embedder.embed("自测文本");
            embeddingInitializable = v1.length == 384 && java.util.Arrays.equals(v1, v2);
        } catch (RuntimeException ex) {
            embeddingInitializable = false;
        }
        boolean corpusLoadable = Files.exists(Path.of(args.corpusPath()));

        String requested = args.backend();
        String retrievalBackend;
        String degradationReason = null;
        if ("PGVECTOR".equalsIgnoreCase(requested)) {
            if (pgReachable && httpReachable) {
                retrievalBackend = "PGVECTOR";
            } else {
                retrievalBackend = "BLOCKED";
                degradationReason = "强制 PGVECTOR 但 PG 端口或应用 HTTP 不可达（PG="
                        + pgReachable + "，HTTP=" + httpReachable + "）";
            }
        } else if ("IN_MEMORY_LOCAL".equalsIgnoreCase(requested)) {
            if (embeddingInitializable && corpusLoadable) {
                retrievalBackend = "IN_MEMORY_LOCAL";
            } else {
                retrievalBackend = "BLOCKED";
                degradationReason = "强制 IN_MEMORY_LOCAL 但 Embedding 或语料不可初始化";
            }
        } else {
            if (pgReachable && httpReachable) {
                retrievalBackend = "PGVECTOR";
            } else if (embeddingInitializable && corpusLoadable) {
                retrievalBackend = "IN_MEMORY_LOCAL";
                degradationReason = "PG 端口或应用 HTTP 不可达（PG=" + pgReachable
                        + "，HTTP=" + httpReachable + "），降级内存后端";
            } else {
                retrievalBackend = "BLOCKED";
                degradationReason = "Embedding 不可初始化或语料缺失";
            }
        }

        return new EnvProbe(timestamp, aiApiKeyPresent, aiMode, chatModelAvailable, fullMode,
                pgReachable, httpReachable, embeddingInitializable, corpusLoadable,
                fullMode, retrievalBackend, degradationReason);
    }

    private static boolean checkConsistency(List<QuestionOutcome> outcomes) {
        for (QuestionOutcome outcome : outcomes) {
            List<RawRecord> rounds = outcome.rounds();
            if (rounds.isEmpty()) {
                continue;
            }
            RawRecord first = rounds.get(0);
            for (int i = 1; i < rounds.size(); i++) {
                RawRecord r = rounds.get(i);
                if (first.documentHit() != r.documentHit()
                        || first.chunkHit() != r.chunkHit()
                        || first.relevantChunkCount() != r.relevantChunkCount()
                        || first.returnedChunkCount() != r.returnedChunkCount()
                        || !first.hitLogicalChunkIds().equals(r.hitLogicalChunkIds())
                        || !java.util.Objects.equals(first.actualDecision(), r.actualDecision())
                        || !first.labels().equals(r.labels())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int exitCode(Map<String, Integer> verdicts) {
        int fail = verdicts.getOrDefault("FAIL", 0);
        int pass = verdicts.getOrDefault("PASS", 0);
        if (fail > 0) {
            return 1;
        }
        if (pass > 0) {
            return 0;
        }
        return 2;
    }

    private static Path runDir(String outputBase, String version, String backend) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Path.of(outputBase, "baseline", version, backend, timestamp);
    }

    private static boolean tcpReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            return java.net.URI.create(baseUrl).getHost();
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }

    private static boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static Args parse(String[] argv) {
        String baseUrl = "http://127.0.0.1:8080";
        String datasetVersion = "v1.0";
        int rounds = 3;
        int warmup = 3;
        String backend = "AUTO";
        double threshold = 0.0;
        int topK = 5;
        String datasetDir = System.getProperty("d37.dataset.dir",
                "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\evidence\\D37\\dataset");
        String corpusPath = System.getProperty("d37.corpus.path",
                "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\D37\\D37_RAG_ESAB"
                        + "\\src\\main\\resources\\knowledge\\equipment-fault-corpus.json");
        String outputBase = System.getProperty("d37.output.base",
                "E:\\AI\\project\\AI-Learning-two-lp\\M3-Java-ds\\docs\\evidence\\D37");
        boolean selftestOnly = false;

        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "-BaseUrl" -> baseUrl = argv[++i];
                case "-DatasetVersion" -> datasetVersion = argv[++i];
                case "-Rounds" -> rounds = Integer.parseInt(argv[++i]);
                case "-Warmup" -> warmup = Integer.parseInt(argv[++i]);
                case "-Backend" -> backend = argv[++i];
                case "-Threshold" -> threshold = Double.parseDouble(argv[++i]);
                case "-TopK" -> topK = Integer.parseInt(argv[++i]);
                case "-DatasetDir" -> datasetDir = argv[++i];
                case "-CorpusPath" -> corpusPath = argv[++i];
                case "-OutputBase" -> outputBase = argv[++i];
                case "-SelftestOnly" -> selftestOnly = true;
                default -> throw new IllegalArgumentException("未知参数: " + argv[i]);
            }
        }
        return new Args(baseUrl, datasetVersion, rounds, warmup, backend, threshold, topK,
                datasetDir, corpusPath, outputBase, selftestOnly);
    }
}
