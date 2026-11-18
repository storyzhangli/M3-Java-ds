package com.zl.d35.rag;

import com.zl.d35.rag.model.RetrievedChunk;

import java.util.List;

/**
 * D35 测试固定样本：引用溯源各场景共用的确定性 Chunk 池。
 *
 * <p>全部为离线 fixture（不依赖数据库与模型）；跨页片段保留完整页码列表，
 * 段落索引真实存在（模拟切分阶段 sources 透传结果）。</p>
 */
public final class CitationTestFixtures {

    private CitationTestFixtures() {
    }

    /** 完整事实问题：有型号、故障码、症状。 */
    public static final String QUERY_FULL = "设备型号是 R2，故障码是 E50，主轴有异响，目前已停机";

    /** 跨页段落片段：页码 15-17，段落索引 3，含 E50 与 2.5MPa，与问题词面强相关。 */
    public static RetrievedChunk chunkR2Active() {
        return new RetrievedChunk("c-r2-01", "doc-r2", "V2.0", 0, "R2 型水泵维护手册",
                List.of(15, 16, 17), 3, "PARAGRAPH",
                "R2 型水泵常见故障：E50 表示排气阀堵塞。处理步骤：先停机泄压，"
                        + "拆卸排气阀清洗。压力阈值应设定为 2.5MPa，过高会导致主轴过热。",
                0.62, 0.38, "ACTIVE", null, null);
    }

    /** 多来源样本 2：另一文档，页码 8，段落索引 1。 */
    public static RetrievedChunk chunkR2Second() {
        return new RetrievedChunk("c-r2-02", "doc-r2-tune", "V1.0", 0, "R2 型水泵联调记录",
                List.of(8), 1, "PARAGRAPH",
                "R2 型水泵联调记录：压力阈值应设定为 2.5MPa。E50 表示排气阀堵塞。",
                0.55, 0.45, "ACTIVE", null, null);
    }

    /** 超长原文样本（用于 excerpt 有限截取验证）。 */
    public static RetrievedChunk chunkLongContent() {
        String content = "R2 型水泵维护手册第 " + "1".repeat(400) + " 章：详细维护步骤说明。";
        return new RetrievedChunk("c-long-01", "doc-r2", "V2.0", 0, "R2 型水泵维护手册",
                List.of(20), 5, "PARAGRAPH", content, 0.50, 0.50, "ACTIVE", null, null);
    }
}
