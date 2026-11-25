package com.zl.d42.knowledge.table;

import com.zl.d42.knowledge.document.ExtractedPage;
import com.zl.d42.knowledge.table.model.ExtractedTable;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 坐标抽取器算法级单测（测试内自绘 PDF，不依赖 temp_docs 夹具；
 * 口径=KP4 冻结：24pt 列间隙 / 列距中位数 60pt 低置信 / 区域首行表头 / 合并覆盖 / 跨页续表）。
 */
class PdfTableExtractorTest {

    private static final PDType1Font FONT =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private final PdfTableExtractor extractor = new PdfTableExtractor();

    /** 绘制工具：x=距左 pt，yTop=距顶 pt（与 TextPosition.yDirAdj 同向）。 */
    private static void text(PDPageContentStream cs, double x, double yTop,
                             float size, String value) throws Exception {
        cs.beginText();
        cs.setFont(FONT, size);
        cs.newLineAtOffset((float) x, (float) (PDRectangle.A4.getHeight() - yTop - size));
        cs.showText(value);
        cs.endText();
    }

    private record Cell(double x, double yTop, float size, String value) {
    }

    private static byte[] pdfWithPages(List<List<Cell>> pageCells) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (List<Cell> cells : pageCells) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    for (Cell cell : cells) {
                        text(cs, cell.x(), cell.yTop(), cell.size(), cell.value());
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static List<Cell> tableAt(double yStart, double[] anchors, List<List<String>> rows) {
        List<Cell> cells = new ArrayList<>();
        double y = yStart;
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                if (!row.get(c).isEmpty()) {
                    cells.add(new Cell(anchors[c], y, 10f, row.get(c)));
                }
            }
            y += 16;
        }
        return cells;
    }

    private static List<Cell> headerTable(double yStart, double[] anchors, List<List<String>> rows) {
        List<Cell> cells = new ArrayList<>();
        double y = yStart;
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            float size = r == 0 ? 11f : 10f;
            for (int c = 0; c < row.size(); c++) {
                if (!row.get(c).isEmpty()) {
                    cells.add(new Cell(anchors[c], y, size, row.get(c)));
                }
            }
            y += 16;
        }
        return cells;
    }

    @Test
    void normalThreeColumnTableParsed() throws Exception {
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(40, 30, 13f, "Fault Code Reference")); // 单列标题不成表
        cells.addAll(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"),
                List.of("E-101", "12.5 mm", "Pressure exceeds limit"),
                List.of("E-102", "380V", "Overvoltage"))));
        byte[] pdf = pdfWithPages(List.of(cells));
        List<ExtractedPage> pages = List.of(new ExtractedPage(1, "text", false));
        List<ExtractedTable> tables;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(document, pages);
        }
        assertEquals(1, tables.size());
        ExtractedTable table = tables.get(0);
        assertEquals(List.of("Code", "Value", "Note"), table.header());
        assertEquals(2, table.rows().size());
        assertEquals(3, table.colCount());
        assertEquals(1, table.pageNumber());
        assertEquals("HIGH", table.confidence());
        assertNull(table.continuationKey());
        assertEquals("number", table.rows().get(0).cells().get(1).type());
        assertEquals("mm", table.rows().get(0).cells().get(1).unit());
    }

    @Test
    void lowColumnSpacingMarksLowConfidence() throws Exception {
        List<Cell> cells = headerTable(80, new double[]{40, 90}, List.of(
                List.of("Code", "Value"),
                List.of("E-1", "1"),
                List.of("E-2", "2")));
        byte[] pdf = pdfWithPages(List.of(cells));
        List<ExtractedTable> tables;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(document, List.of(new ExtractedPage(1, "t", false)));
        }
        assertEquals(1, tables.size());
        assertTrue(tables.get(0).detectFlags().contains(TableDegrade.LOW_CONFIDENCE));
        assertEquals("LOW", tables.get(0).confidence());
    }

    @Test
    void mergedOverrunCellFlagged() throws Exception {
        List<Cell> cells = new ArrayList<>();
        // 表头 + 正常行 + 合并行（A 列长文本越过 140 锚且该行 B 列无独立起点）
        cells.addAll(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"))));
        cells.add(new Cell(40, 96, 10f, "E-101"));
        cells.add(new Cell(140, 96, 10f, "12.5 mm"));
        cells.add(new Cell(300, 96, 10f, "ok"));
        cells.add(new Cell(40, 112, 10f, "Merged BC across column region"));
        cells.add(new Cell(300, 112, 10f, "warn"));
        byte[] pdf = pdfWithPages(List.of(cells));
        List<ExtractedTable> tables;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(document, List.of(new ExtractedPage(1, "t", false)));
        }
        assertEquals(1, tables.size());
        assertTrue(tables.get(0).detectFlags().contains(TableDegrade.MERGED_CELL));
    }

    @Test
    void sameHeaderNextPageBecomesContinuation() throws Exception {
        List<List<String>> headerAndRows = List.of(
                List.of("Code", "Value", "Note"));
        List<Cell> page1 = new ArrayList<>(headerTable(80,
                new double[]{40, 140, 300}, headerAndRows));
        page1.addAll(tableAt(96, new double[]{40, 140, 300}, List.of(
                List.of("E-1", "1", "a"), List.of("E-2", "2", "b"))));
        List<Cell> page2 = new ArrayList<>(headerTable(80,
                new double[]{40, 140, 300}, headerAndRows));
        page2.addAll(tableAt(96, new double[]{40, 140, 300}, List.of(
                List.of("E-3", "3", "c"))));
        byte[] pdf = pdfWithPages(List.of(page1, page2));
        List<ExtractedTable> tables;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(document, List.of(
                    new ExtractedPage(1, "t", false), new ExtractedPage(2, "t", false)));
        }
        assertEquals(2, tables.size());
        ExtractedTable continuation = tables.get(1);
        assertNotNull(continuation.continuationKey());
        assertEquals(1, continuation.continuationKey()[0]);
        assertTrue(continuation.detectFlags().contains(TableDegrade.CONTINUATION_HEADER_SKIPPED));
        assertEquals(1, continuation.rows().size());
    }

    @Test
    void imageOnlyBlankPageYieldsDegradedRecord() throws Exception {
        // 空页（无文本）+ blankPage=true 且无图 → 不产记录；有图判定在夹具轨（A5）覆盖
        List<Cell> empty = List.of();
        byte[] pdf = pdfWithPages(List.of(empty));
        List<ExtractedTable> tables;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(document, List.of(new ExtractedPage(1, "", true)));
        }
        assertTrue(tables.isEmpty());
    }
    /** D13b（R01 S2 对账回归）：表头字宽大、列间隙落在 12–24pt 区间被原口径并成单段，
     *  须经半间隙重切恢复为区域首行表头，数据行不得整体上移错位。 */
    @Test
    void wideHeaderRowRecoveredViaHalfGapRetry() throws Exception {
        List<Cell> page = new ArrayList<>();
        page.add(new Cell(40, 64, 11f, "HeadA"));
        page.add(new Cell(90, 64, 11f, "HeadB"));
        page.add(new Cell(140, 64, 11f, "HeadC"));
        page.add(new Cell(190, 64, 11f, "HeadD"));
        page.addAll(tableAt(80, new double[]{40, 90, 140, 190},
                List.of(List.of("A1v", "B1v", "C1v", "D1v"),
                        List.of("A2v", "B2v", "C2v", "D2v"))));
        byte[] pdf = pdfWithPages(List.of(page));
        List<ExtractedTable> tables;
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            tables = extractor.extract(doc, List.of(new ExtractedPage(1, "", false)));
        }
        assertEquals(1, tables.size());
        assertEquals(List.of("HeadA", "HeadB", "HeadC", "HeadD"), tables.get(0).header());
        assertEquals(2, tables.get(0).rows().size());
        assertEquals("A1v", tables.get(0).rows().get(0).cells().get(0).value());
    }

    // ------------------------------------------------------------------
    // R02-02：列分桶静默合并修复（异常场景矩阵）
    // ------------------------------------------------------------------

    private List<ExtractedTable> extractOf(byte[] pdf) throws Exception {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return extractor.extract(document, List.of(new ExtractedPage(1, "t", false)));
        }
    }

    /** R02 场景2：两个独立单元格落入同一列桶——不得静默拼接，surplus 原文以 EXTRA_COL 保留。 */
    @Test
    void independentCellsInSameBucketAreNotSilentlyJoined() throws Exception {
        List<Cell> cells = new ArrayList<>(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"))));
        cells.add(new Cell(40, 96, 10f, "E-101"));
        cells.add(new Cell(140, 96, 10f, "12.5 mm"));
        cells.add(new Cell(300, 96, 10f, "ok"));
        // 异常行：B 桶内 "V"(x=140) 与独立单元格 "W"(x=210，距 B 锚 70pt、距 C 锚 90pt、
        // 与前一 run 间隙 ~63pt > 低置信列距 60pt) —— R01 会拼成 "VW" 静默放行
        cells.add(new Cell(40, 112, 10f, "E-102"));
        cells.add(new Cell(140, 112, 10f, "V"));
        cells.add(new Cell(210, 112, 10f, "W"));
        List<ExtractedTable> tables = extractOf(pdfWithPages(List.of(cells)));
        assertEquals(1, tables.size());
        ExtractedTable table = tables.get(0);
        assertTrue(table.detectFlags().contains(TableDegrade.EXTRA_CELL),
                "EXTRA_CELL flag expected, got " + table.detectFlags());
        var anomalyRow = table.rows().get(1);
        assertEquals("V", anomalyRow.cells().get(1).value()); // 未拼接
        assertEquals("W", anomalyRow.cells().get(3).value()); // EXTRA_COL_1 原文保留
        assertTrue(anomalyRow.cells().get(2).isMissing());    // Note 列缺失 null 不串列
        assertTrue(anomalyRow.flags().contains(TableDegrade.EXTRA_CELL));
    }

    /** R02 场景3：单元格横向偏移过大——错位标记 + 原文保留，不猜归属。 */
    @Test
    void shiftedCellProducesMisalignmentFlag() throws Exception {
        List<Cell> cells = new ArrayList<>(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"))));
        cells.add(new Cell(40, 96, 10f, "E-101"));
        cells.add(new Cell(140, 96, 10f, "12.5 mm"));
        cells.add(new Cell(300, 96, 10f, "ok"));
        cells.add(new Cell(40, 112, 10f, "E-102"));
        cells.add(new Cell(175, 112, 10f, "12.5 mm")); // 距 B 锚 35pt > 24pt 阈值
        cells.add(new Cell(300, 112, 10f, "warn"));
        List<ExtractedTable> tables = extractOf(pdfWithPages(List.of(cells)));
        ExtractedTable table = tables.get(0);
        assertTrue(table.detectFlags().contains(TableDegrade.COLUMN_MISALIGNMENT),
                "COLUMN_MISALIGNMENT expected, got " + table.detectFlags());
        assertEquals("12.5 mm", table.rows().get(1).cells().get(1).value()); // 原文仍在 B 列可读
        assertTrue(table.rows().get(1).flags().contains(TableDegrade.COLUMN_MISALIGNMENT));
    }

    /** R02 场景4：行多出一个独立单元格（越过末列右缘大间隙）——异常信息保留不静默拼接。 */
    @Test
    void extraCellBeyondLastColumnKept() throws Exception {
        List<Cell> cells = new ArrayList<>(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"))));
        cells.add(new Cell(40, 96, 10f, "E-101"));
        cells.add(new Cell(140, 96, 10f, "12.5 mm"));
        cells.add(new Cell(300, 96, 10f, "ok"));
        cells.add(new Cell(40, 112, 10f, "E-102"));
        cells.add(new Cell(140, 112, 10f, "400 V"));
        cells.add(new Cell(300, 112, 10f, "note"));
        cells.add(new Cell(398, 112, 10f, "X")); // 末列外独立单元格（距前段尾 ~76pt）
        List<ExtractedTable> tables = extractOf(pdfWithPages(List.of(cells)));
        var anomalyRow = tables.get(0).rows().get(1);
        assertEquals("note", anomalyRow.cells().get(2).value());
        assertEquals("X", anomalyRow.cells().get(3).value()); // EXTRA_COL_1 保留
        assertTrue(anomalyRow.flags().contains(TableDegrade.EXTRA_CELL));
    }

    /** R02 场景7：列内正常长文本片段（间隙 24–60pt 且未越列跨度）仍拼接，不误判独立单元格。 */
    @Test
    void inColumnTextFragmentStillJoinedNotFlagged() throws Exception {
        List<Cell> cells = new ArrayList<>(headerTable(80, new double[]{40, 140, 300}, List.of(
                List.of("Code", "Value", "Note"))));
        cells.add(new Cell(40, 96, 10f, "E-101"));
        cells.add(new Cell(140, 96, 10f, "Over"));
        cells.add(new Cell(200, 96, 10f, "pressure")); // 列内片段：gap≈38pt、未越 300 锚
        cells.add(new Cell(300, 96, 10f, "ok"));
        cells.add(new Cell(40, 112, 10f, "E-102"));
        cells.add(new Cell(140, 112, 10f, "220 V"));
        cells.add(new Cell(300, 112, 10f, "warn"));
        List<ExtractedTable> tables = extractOf(pdfWithPages(List.of(cells)));
        ExtractedTable table = tables.get(0);
        assertFalse(table.detectFlags().contains(TableDegrade.EXTRA_CELL),
                "fragment must not be flagged, got " + table.detectFlags());
        assertEquals("Overpressure", table.rows().get(0).cells().get(1).value());
    }
}
