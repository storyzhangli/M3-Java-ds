package com.zl.d41.knowledge.table;

import com.zl.d41.knowledge.document.ExtractedPage;
import com.zl.d41.knowledge.table.model.ExtractedTable;
import com.zl.d41.knowledge.table.model.TableCell;
import com.zl.d41.knowledge.table.model.TableRowData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** D41 R01 S2 差异根因探针：a2/a3 行单元结构 + 抽取结果 + UnitTyping 判定。 */
public final class ProbeD41Diff {

    public static void main(String[] args) throws Exception {
        String fix = args[0];
        PdfTableExtractor ex = new PdfTableExtractor();
        Method cr = PdfTableExtractor.class.getDeclaredMethod("collectRows", PDDocument.class, int.class);
        Method sc = PdfTableExtractor.class.getDeclaredMethod("splitCells", List.class);
        cr.setAccessible(true);
        sc.setAccessible(true);
        for (String name : new String[]{"pdfs/d41-fx-anomaly-a2.pdf", "pdfs/d41-fx-anomaly-a3.pdf"}) {
            System.out.println("##### " + name);
            try (PDDocument doc = Loader.loadPDF(new File(fix, name))) {
                int total = doc.getNumberOfPages();
                for (int p = 1; p <= total; p++) {
                    System.out.println("--- page " + p);
                    @SuppressWarnings("unchecked")
                    List<List<PdfTableExtractor.Pos>> rows = (List<List<PdfTableExtractor.Pos>>) cr.invoke(ex, doc, p);
                    for (List<PdfTableExtractor.Pos> row : rows) {
                        @SuppressWarnings("unchecked")
                        List<PdfTableExtractor.CellRun> cells =
                                (List<PdfTableExtractor.CellRun>) sc.invoke(ex, row);
                        System.out.printf("cells y=%.1f -> %d:", row.get(0).y(), cells.size());
                        for (PdfTableExtractor.CellRun c : cells) {
                            System.out.printf(" [x=%.1f,e=%.1f,%s]", c.x(), c.end(), c.text());
                        }
                        System.out.println();
                    }
                }
                List<ExtractedPage> pages = new ArrayList<>();
                for (int p = 1; p <= total; p++) {
                    pages.add(new ExtractedPage(p, "", false));
                }
                List<ExtractedTable> tables = ex.extract(doc, pages);
                for (ExtractedTable t : tables) {
                    System.out.println("TABLE page=" + t.pageNumber() + " idx=" + t.tableIndex()
                            + " header=" + t.header() + " cols=" + t.colCount() + " flags=" + t.detectFlags());
                    for (TableRowData r : t.rows()) {
                        StringBuilder sb = new StringBuilder("  row" + r.rowIndex() + ": ");
                        for (TableCell c : r.cells()) {
                            sb.append(c.isMissing() ? "<null>" : c.value() + "/" + c.type() + "/" + c.unit()).append(" | ");
                        }
                        System.out.println(sb);
                    }
                }
            }
        }
        System.out.println("##### UnitTyping probes");
        for (String s : List.of("25x52x15", "52x65x8", "DN25", "1500 rpm", "Merged BC across", "Col_A")) {
            for (String h : List.of("Spec", "数量")) {
                TableCell c = UnitTyping.typeOf(h, s);
                System.out.printf("typeOf(%s, %s) -> text=%s type=%s unit=%s%n", h, s, c.value(), c.type(), c.unit());
            }
        }
    }
}


