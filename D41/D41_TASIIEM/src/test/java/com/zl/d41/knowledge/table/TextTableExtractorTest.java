package com.zl.d41.knowledge.table;

import com.zl.d41.knowledge.table.model.ExtractedTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本管道表抽取器单测（D3 主路径 + A3 续表 + 分隔行容忍 + 缺列对齐旗标）。
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

    @Test
    void differentColumnCountRowBreaksRegionNotShiftColumns() {
        String text = """
                | Code | Value | Note |
                | E-1 | 1 | ok |
                | E-2 | 2 |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        // KP4 断裂规则：列数不一致行不并入原表（其后孤立行不足 2 行不成表），绝不串列
        assertEquals(1, tables.size());
        assertEquals(1, tables.get(0).rows().size());
        assertEquals("ok", tables.get(0).rows().get(0).cells().get(2).value());
    }

    @Test
    void missingCellFlaggedNotColumnShifted() {
        String text = """
                | Code | Value | Note |
                | E-1 |  | 超限 |
                | E-2 | 2 | 正常 |
                """;
        List<ExtractedTable> tables = extractor.extract(text);
        assertEquals(1, tables.size());
        // 中段空单元格保留空串占位，不串列：Note 列仍为“超限”
        assertEquals("超限", tables.get(0).rows().get(0).cells().get(2).value());
    }
}
