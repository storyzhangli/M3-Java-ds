package com.zl.d43.knowledge.table;

import com.zl.d43.knowledge.table.model.ExtractedTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 表格抽取分支单测（KP1 裁决启用：Jsoup 传递依赖，H3 合规）。
 */
class HtmlTableExtractorTest {

    private final HtmlTableExtractor extractor = new HtmlTableExtractor();

    @Test
    void basicTableWithHeaderAndTypedCells() {
        String html = """
                <html><body>
                <table>
                  <tr><th>Code</th><th>Value</th></tr>
                  <tr><td>E-1</td><td>12.5 mm</td></tr>
                  <tr><td>E-2</td><td>4.2</td></tr>
                </table>
                </body></html>
                """;
        List<ExtractedTable> tables = extractor.extract(html);
        assertEquals(1, tables.size());
        assertEquals(List.of("Code", "Value"), tables.get(0).header());
        assertEquals(2, tables.get(0).rows().size());
        assertEquals("number", tables.get(0).rows().get(0).cells().get(1).type());
        assertEquals("mm", tables.get(0).rows().get(0).cells().get(1).unit());
    }

    @Test
    void shortRowMarksColumnMisalignment() {
        String html = """
                <table>
                  <tr><th>A</th><th>B</th><th>C</th></tr>
                  <tr><td>1</td><td>2</td><td>3</td></tr>
                  <tr><td>4</td></tr>
                </table>
                """;
        List<ExtractedTable> tables = extractor.extract(html);
        assertEquals(1, tables.size());
        assertTrue(tables.get(0).detectFlags().contains(TableDegrade.COLUMN_MISALIGNMENT));
        assertTrue(tables.get(0).rows().get(1).cells().get(1).isMissing());
    }

    @Test
    void singleRowTableIgnored() {
        List<ExtractedTable> tables = extractor.extract(
                "<table><tr><th>A</th><th>B</th></tr></table>");
        assertTrue(tables.isEmpty());
    }
}
