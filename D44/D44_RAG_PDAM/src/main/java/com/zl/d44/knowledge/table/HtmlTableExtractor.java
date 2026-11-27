package com.zl.d44.knowledge.table;

import com.zl.d44.knowledge.table.TableDegrade;
import com.zl.d44.knowledge.table.UnitTyping;
import com.zl.d44.knowledge.table.model.ExtractedTable;
import com.zl.d44.knowledge.table.model.TableCell;
import com.zl.d44.knowledge.table.model.TableRowData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * HTML 表格抽取可选分支（D3/KP1 裁决启用：既有 Jsoup 1.22.2 传递依赖，非新框架 H3 合规）。
 * 单页文档页码固定 1；首行含 &lt;th&gt; 或首行即表头（快照口径同 PDF/文本轨）。
 */
public final class HtmlTableExtractor {

    public List<ExtractedTable> extract(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        List<ExtractedTable> tables = new ArrayList<>();
        Elements elements = Jsoup.parse(html).select("table");
        int tableIndex = 0;
        for (Element table : elements) {
            Elements trs = table.select("tr");
            if (trs.size() < 2) {
                continue;
            }
            Element headerRow = trs.first();
            if (headerRow == null) {
                continue;
            }
            List<String> header = new ArrayList<>();
            for (Element cell : headerRow.select("th,td")) {
                header.add(TableTexts.cleanCell(cell.text()));
            }
            int cols = header.size();
            if (cols < 2) {
                continue;
            }
            List<TableRowData> rows = new ArrayList<>();
            for (int r = 1; r < trs.size(); r++) {
                Element tr = trs.get(r);
                List<String> values = new ArrayList<>();
                for (Element cell : tr.select("th,td")) {
                    values.add(TableTexts.cleanCell(cell.text()));
                }
                List<TableCell> cells = new ArrayList<>(cols);
                List<String> rowFlags = new ArrayList<>();
                boolean missing = false;
                for (int c = 0; c < cols; c++) {
                    String value = c < values.size() ? values.get(c) : null;
                    if (value == null) {
                        missing = true;
                        cells.add(TableCell.missing(header.get(c)));
                    } else {
                        cells.add(UnitTyping.typeOf(header.get(c), value));
                    }
                }
                if (missing) {
                    rowFlags.add(TableDegrade.COLUMN_MISALIGNMENT);
                }
                if (cells.stream().allMatch(TableCell::isMissing)) {
                    continue;
                }
                String rowText = String.join(" ", cells.stream().map(TableCell::textOrEmpty).toList())
                        .replaceAll("\\s+", " ").strip();
                rows.add(new TableRowData(rows.size(), cells, rowText, r + 1, rowFlags));
            }
            Set<String> flags = rows.stream().flatMap(row -> row.flags().stream())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            tables.add(new ExtractedTable(1, tableIndex++, header, rows, cols, flags, "HIGH",
                    null, 1, trs.size(), "ACTIVE"));
        }
        return tables;
    }
}
