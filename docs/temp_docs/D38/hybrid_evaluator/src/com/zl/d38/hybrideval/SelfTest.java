package com.zl.d38.hybrideval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 纯函数自测（先于正式评测，全 PASS 才允许跑主矩阵——P4 门禁）。
 *
 * <p>覆盖：RRF 手算夹具 ≥8 项（与后端单测同口径独立实现，交叉验证公式一致性）、
 * 同分 chunkId 升序稳定性、投影口径（浮点不进投影、排序进投影）。
 * 不访问网络、不依赖 Spring。</p>
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static void run(Path outFile) {
        List<String> results = new java.util.ArrayList<>();

        // 夹具 1：两路都命中，BOTH 项排最前（向量rank2 1/62 + 关键词rank1 1/61）
        var t1 = RrfFusion.fuse(List.of("A", "B"), List.of("B", "C"),
                Map.of(), Map.of(), 60, 5);
        results.add(check("RRF-01 BOTH 项首位且分数=1/62+1/61",
                "B".equals(t1.get(0).chunkId())
                        && Math.abs(t1.get(0).rrfScore() - (1.0 / 62.0 + 1.0 / 61.0)) < 1e-12));

        // 夹具 2：向量第 1 名单路 vs 关键词第 2 名单路，前者胜（同分时按 chunkId 字典序，W<X）
        var t2 = RrfFusion.fuse(List.of("X"), List.of("W", "Y"),
                Map.of(), Map.of(), 60, 5);
        double xScore = t2.stream().filter(e -> e.chunkId().equals("X"))
                .findFirst().orElseThrow().rrfScore();
        double yScore = t2.stream().filter(e -> e.chunkId().equals("Y"))
                .findFirst().orElseThrow().rrfScore();
        results.add(check("RRF-02 向量rank1(1/61)>关键词rank2(1/62)",
                "W".equals(t2.get(0).chunkId())
                        && Math.abs(xScore - 1.0 / 61.0) < 1e-12
                        && Math.abs(yScore - 1.0 / 62.0) < 1e-12));

        // 夹具 3：origin 标注正确
        results.add(check("RRF-03 origin 三态标注",
                "BOTH".equals(t1.get(0).origin())
                        && "VECTOR_ONLY".equals(t1.get(1).origin())
                        && "KEYWORD_ONLY".equals(t1.stream()
                        .filter(e -> e.chunkId().equals("C")).findFirst().orElseThrow().origin())));

        // 夹具 4：缺席不贡献（向量路未出现的 chunk 只有关键词分量）
        var t4 = RrfFusion.fuse(List.of("A"), List.of("A", "Z"),
                Map.of(), Map.of(), 60, 5);
        double zScore = t4.stream().filter(e -> e.chunkId().equals("Z"))
                .findFirst().orElseThrow().rrfScore();
        results.add(check("RRF-04 缺席路不贡献分数",
                Math.abs(zScore - 1.0 / 62.0) < 1e-12));

        // 夹具 5：同 rrfScore 时 chunkId 升序稳定（Z 与 A 各 rank1 同分 → A 在前）
        var t5 = RrfFusion.fuse(List.of("Z"), List.of("A"),
                Map.of(), Map.of(), 60, 5);
        results.add(check("RRF-05 同分 chunkId 升序",
                "A".equals(t5.get(0).chunkId()) && "Z".equals(t5.get(1).chunkId())
                        && Math.abs(t5.get(0).rrfScore() - t5.get(1).rrfScore()) < 1e-15));

        // 夹具 6：topK 截断
        var t6 = RrfFusion.fuse(List.of("a", "b", "c", "d", "e", "f"), List.of(),
                Map.of(), Map.of(), 60, 3);
        results.add(check("RRF-06 topK=3 截断且保序",
                t6.size() == 3 && "a".equals(t6.get(0).chunkId())
                        && "c".equals(t6.get(2).chunkId())));

        // 夹具 7：k 参数敏感（k=20 时 rank1 分数=1/21）
        var t7 = RrfFusion.fuse(List.of("A"), List.of(),
                Map.of(), Map.of(), 20, 5);
        results.add(check("RRF-07 rrfK=20 生效",
                Math.abs(t7.get(0).rrfScore() - 1.0 / 21.0) < 1e-12));

        // 夹具 8：rank 从 1 起（rank3 → 1/63，不是 1/62 或 1/64）
        var t8 = RrfFusion.fuse(List.of("p", "q", "r"), List.of(),
                Map.of(), Map.of(), 60, 5);
        results.add(check("RRF-08 rank 从 1 起算",
                Math.abs(t8.get(2).rrfScore() - 1.0 / 63.0) < 1e-12));

        // 夹具 9：确定性（同一输入两次融合输出逐字段一致）
        var t9a = RrfFusion.fuse(List.of("m", "n"), List.of("n", "m"),
                Map.of(), Map.of(), 60, 5);
        var t9b = RrfFusion.fuse(List.of("m", "n"), List.of("n", "m"),
                Map.of(), Map.of(), 60, 5);
        results.add(check("RRF-09 融合确定性", t9a.equals(t9b)));

        // 投影夹具：浮点不进投影、排序进投影
        Evaluator.RawRecord r10 = fixture("QA-010", List.of("c1", "c2"), 0.91);
        Evaluator.RawRecord r10b = fixture("QA-010", List.of("c1", "c2"), 0.42);
        results.add(check("PROJ-01 浮点分不同但投影一致",
                r10.qualityProjection().equals(r10b.qualityProjection())));
        Evaluator.RawRecord r10c = fixture("QA-010", List.of("c2", "c1"), 0.91);
        results.add(check("PROJ-02 排序不同投影必须不同（排序进投影）",
                !r10.qualityProjection().equals(r10c.qualityProjection())));

        long pass = results.stream().filter(s -> s.startsWith("PASS")).count();
        long fail = results.size() - pass;
        StringBuilder sb = new StringBuilder();
        sb.append("# D38 R02 hybrideval 纯函数自测报告\n\n");
        sb.append("自测项: ").append(results.size()).append('\n');
        sb.append("PASS: ").append(pass).append('\n');
        sb.append("FAIL: ").append(fail).append('\n');
        sb.append("\n## 明细\n\n");
        for (String line : results) {
            sb.append("- ").append(line).append('\n');
        }
        String report = sb.toString();
        System.out.println(report);
        try {
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, report, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            System.err.println("自测报告落盘失败: " + ex.getMessage());
            System.exit(1);
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static Evaluator.RawRecord fixture(String id, List<String> chunkIds, double score) {
        return new Evaluator.RawRecord(id, "ANSWERABLE", 1, "HYBRID_RRF",
                chunkIds.size(), 1, chunkIds.size(), 1, 1, 0,
                true, true, "ANSWER", "ANSWER", "SUCCESS", null,
                List.of(), 5, 50, 60,
                List.of("DOC-X"), List.of("L1", "L2"), chunkIds,
                List.of(score, score), List.of("DOC-X"), List.of("L1", "L2"), "PASS",
                2, 1, List.of("c1|BOTH|1|1"), false, false);
    }

    private static String check(String name, boolean ok) {
        return (ok ? "PASS" : "FAIL") + " - " + name;
    }
}
