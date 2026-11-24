import com.zl.d41.knowledge.document.ExtractedPage;
import com.zl.d41.knowledge.table.PdfTableExtractor;
import com.zl.d41.knowledge.table.model.ExtractedTable;
import com.zl.d41.knowledge.table.model.TableCell;
import com.zl.d41.knowledge.table.model.TableRowData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * D41 R02-01：真实公开设备手册解析探针（只读，不落库、不走 HTTP）。
 *
 * 用法：java -cp "target/classes;依赖cp" ProbeD41Manual.java <pdf路径> <输出目录>
 * 输出：
 *   1) stdout  —— 手册元数据 + 全部候选表格清单（页/表号/列/行/状态/标志/表头）
 *   2) 输出目录/manual-tables.tsv —— 每张表全部行原文（人工对照用）
 */
public final class ProbeD41Manual {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ProbeD41Manual <pdf> <outDir>");
            System.exit(2);
        }
        Path pdf = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        List<ExtractedPage> pages = new ArrayList<>();
        List<ExtractedTable> tables;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            System.out.println("META title=" + nz(info.getTitle()));
            System.out.println("META author=" + nz(info.getAuthor()));
            System.out.println("META producer=" + nz(info.getProducer()));
            System.out.println("META creationDate=" + info.getCreationDate());
            System.out.println("META pages=" + document.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int p = 1; p <= document.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String content = stripper.getText(document);
                pages.add(new ExtractedPage(p, content, content.isBlank()));
            }
            long textChars = pages.stream().mapToLong(ExtractedPage::characterCount).sum();
            System.out.println("META textChars=" + textChars + " (text-layer PDF check)");

            tables = new PdfTableExtractor().extract(document, pages);
        }
        System.out.println("TABLES total=" + tables.size());
        StringBuilder tsv = new StringBuilder();
        for (ExtractedTable table : tables) {
            System.out.printf("TABLE page=%d index=%d cols=%d rows=%d status=%s flags=%s header=%s%n",
                    table.pageNumber(), table.tableIndex(), table.colCount(),
                    table.rows().size(), table.degradeStatus(), table.detectFlags(), table.header());
            tsv.append("### page=").append(table.pageNumber())
                    .append(" tableIndex=").append(table.tableIndex())
                    .append(" status=").append(table.degradeStatus())
                    .append(" flags=").append(table.detectFlags()).append('\n');
            tsv.append("HEADER\t").append(String.join("\t",
                    table.header().stream().map(ProbeD41Manual::nz).toList())).append('\n');
            for (TableRowData row : table.rows()) {
                List<String> cells = new ArrayList<>();
                for (TableCell cell : row.cells()) {
                    cells.add(cell.value() == null ? "<null>" : cell.value().replace("\t", " "));
                }
                tsv.append("ROW").append(row.rowIndex()).append('\t')
                        .append(String.join("\t", cells))
                        .append("\tline=").append(row.sourceLineNumber())
                        .append("\tflags=").append(row.flags()).append('\n');
            }
            tsv.append('\n');
        }
        Path tsvPath = outDir.resolve("manual-tables.tsv");
        Files.writeString(tsvPath, tsv, StandardCharsets.UTF_8);
        System.out.println("WROTE " + tsvPath.toAbsolutePath());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
