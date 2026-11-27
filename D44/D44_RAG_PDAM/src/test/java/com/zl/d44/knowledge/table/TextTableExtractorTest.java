package com.zl.d44.knowledge.table;

import com.zl.d44.knowledge.table.model.ExtractedTable;
import com.zl.d44.knowledge.table.model.TableRowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本管道表抽取器单测（D3 主路径 + A3 续表 + 分隔行容忍 + 缺列对齐旗标 + R02-03 错位行保留）。
 */
class TextTableExtractorTest {

    private final TextTableExtractor extractor = new TextTableExtractor();

    @Test
    void basicPipeTableWithSeparatorRow() {
        String text = """
                故障代码表如下说明行
                | Code | Value | Note |
                |------|-------|------|
                | E-101 | 12.5 mm | 压力超限 |
                | E-102 | 380V | 过压 |
                表后文字
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(1, tables.size());
        ExtractedTable table = tables.get(0);
        assertEquals(List.of("Code", "Value", "Note"), table.header());
        assertEquals(2, table.rows().size());
        assertEquals(3, table.colCount());
        assertEquals(1, table.pageNumber());
        assertEquals("number", table.rows().get(0).cells().get(1).type());
        assertEquals("mm", table.rows().get(0).cells().get(1).unit());
        assertTrue(table.detectFlags().isEmpty());
    }

    @Test
    void repeatedHeaderBecomesContinuation() {
        String text = """
                | Code | Value |
                | E-1 | 1 |
                | E-2 | 2 |
                | Code | Value |
                | E-3 | 3 |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(2, tables.size());
        ExtractedTable continuation = tables.get(1);
        assertEquals(1, continuation.tableIndex());
        assertNotNull(continuation.continuationKey());
        assertEquals(0, continuation.continuationKey()[1]);
        assertTrue(continuation.detectFlags().contains(TableDegrade.CONTINUATION_HEADER_SKIPPED));
        assertEquals(1, continuation.rows().size());
    }

    /** R02-03 场景2/末行：行缺少一列（其后无同列数行）→ 异常组表保留，不再无痕丢失。 */
    @Test
    void misalignedRowIsKeptInAnomalyGroupNotSilentlyDropped() {
        String text = """
                | Code | Value | Note |
                | E-1 | 1 | ok |
                | E-2 | 2 |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(2, tables.size());
        ExtractedTable normal = tables.get(0);
        assertEquals(1, normal.rows().size());
        assertEquals("ok", normal.rows().get(0).cells().get(2).value()); // 正常行不受影响
        ExtractedTable anomaly = tables.get(1);
        assertEquals("DEGRADED", anomaly.degradeStatus());
        assertTrue(anomaly.detectFlags().contains(TableDegrade.MISALIGNED_LINE_GROUP));
        assertTrue(anomaly.detectFlags().contains("EXPECTED_COLS_3_ACTUAL_2"));
        TableRowData kept = anomaly.rows().get(0);
        assertEquals(3, kept.sourceLineNumber()); // 源行位置可追溯
        assertEquals("E-2", kept.cells().get(0).value());
        assertTrue(kept.cells().get(2).isMissing()); // 缺失列 null 占位不串列
    }

    /** R02-03 场景3：行额外增加一列（末行错位）→ 超列值原文保留 + 列数信息。 */
    @Test
    void misalignedRowWithExtraColumnKeepsRawValue() {
        String text = """
                | Code | Value | Note |
                | E-1 | 1 | ok |
                | E-2 | 2 | warn | stray |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(2, tables.size());
        ExtractedTable anomaly = tables.get(1);
        assertTrue(anomaly.detectFlags().contains("EXPECTED_COLS_3_ACTUAL_4"));
        TableRowData kept = anomaly.rows().get(0);
        assertEquals(3, kept.sourceLineNumber());
        assertEquals("warn", kept.cells().get(2).value());
        assertEquals("stray", kept.cells().get(3).value()); // EXTRA_COL_1 原文保留
        assertEquals("EXTRA_COL_1", kept.cells().get(3).header());
        assertTrue(kept.flags().contains(TableDegrade.COLUMN_MISALIGNMENT));
    }

    /** R02-03 场景5：错位行后仅有一行且列数一致 → 换表成立成新区（不丢失、不污染异常组）。 */
    @Test
    void switchToNewColumnCountStillFormsTable() {
        String text = """
                | Code | Value | Note |
                | E-1 | 1 | ok |
                | Comp | Size |
                | Motor | 3 kW |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(2, tables.size());
        assertEquals(List.of("Code", "Value", "Note"), tables.get(0).header());
        assertEquals(List.of("Comp", "Size"), tables.get(1).header());
        assertEquals(1, tables.get(1).rows().size());
        assertEquals("3 kW", tables.get(1).rows().get(0).cells().get(1).value());
        assertEquals("ACTIVE", tables.get(1).degradeStatus()); // 是换表不是异常组
    }

    /** R02-03 场景：错位行夹在正常行中间 → 正常表不混入错位行，异常组独立可查。 */
    @Test
    void midTableMisalignmentIsolated() {
        String text = """
                | Code | Value | Note |
                | E-1 | 1 | ok |
                | E-2 | 2 | warn | extra |
                | E-3 | 3 | fine |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(2, tables.size());
        assertEquals(2, tables.get(0).rows().size()); // E-1、E-3 正常
        assertEquals("E-3", tables.get(0).rows().get(1).cells().get(0).value());
        assertEquals(1, tables.get(1).rows().size()); // E-2 异常组保留
        assertEquals(3, tables.get(1).rows().get(0).sourceLineNumber());
    }

    /** R02-03 场景6 + R01 回归：中间空单元格 null 占位不串列；重复表头续表语义不回退。 */
    @Test
    void missingCellFlaggedNotColumnShifted() {
        String text = """
                | Code | Value | Note |
                | E-1 |  | 超限 |
                | E-2 | 2 | 正常 |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(1, tables.size());
        // 中段空单元格保留空串占位，不串列：Note 列仍为"超限"
        assertEquals("超限", tables.get(0).rows().get(0).cells().get(2).value());
    }
}
