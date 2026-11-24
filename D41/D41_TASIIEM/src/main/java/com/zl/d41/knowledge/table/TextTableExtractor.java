package com.zl.d41.knowledge.table;

import com.zl.d41.knowledge.table.model.ExtractedTable;
import com.zl.d41.knowledge.table.model.TableCell;
import com.zl.d41.knowledge.table.model.TableRowData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本管道表抽取器（D3 主路径：txt/md 的 | 分隔表；单页文档页码固定 1）。
 *
 * <p>KP4 口径：分隔行（|---|）区域内容忍跳过、不入数据行、不断裂区域；
 * 重复表头行=续表新表（表头不入数据行，continuationKey 指向首表，D10/A3）；
 * 空单元格 null 占位不串列；全 null 行不产生记录；列数不一致处断裂新表。</p>
 */
public final class TextTableExtractor {

    private static final Pattern SEPARATOR_CELL = Pattern.compile("^:?-{2,}:?$");

    record Candidate(int lineNumber, String[] cells, boolean separator) {
    }

    public List<ExtractedTable> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\\R");
        List<ExtractedTable> tables = new ArrayList<>();
        List<Candidate> region = new ArrayList<>();
        int expectedCols = -1;
        for (int i = 0; i <= lines.length; i++) {
            Candidate candidate = i < lines.length ? classify(lines[i], i + 1) : null;
            if (candidate != null) {
                if (!candidate.separator() && expectedCols != -1
                        && candidate.cells().length != expectedCols) {
                    flush(tables, region, expectedCols);
                    region = new ArrayList<>();
                    expectedCols = -1;
                }
                if (!candidate.separator() && expectedCols == -1) {
                    expectedCols = candidate.cells().length;
                }
                region.add(candidate);
            } else {
                flush(tables, region, expectedCols);
                region = new ArrayList<>();
                expectedCols = -1;
            }
        }
        return tables;
    }

    /** 行分类：管道结构行（≥2 非空段）或分隔行；其余 null（区域边界）。 */
    private Candidate classify(String line, int lineNumber) {
        String stripped = line.strip();
        if (stripped.isEmpty() || !stripped.contains("|")) {
            return null;
        }
        String[] raw = stripped.split("\\|", -1);
        List<String> parts = new ArrayList<>();
        for (String part : raw) {
            parts.add(part.strip());
        }
        if (!parts.isEmpty() && parts.get(0).isEmpty()) {
            parts.remove(0);
        }
        if (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        if (parts.size() < 2) {
            return null;
        }
        boolean separator = parts.stream().allMatch(p -> !p.isEmpty() && SEPARATOR_CELL.matcher(p).matches());
        if (separator) {
            return new Candidate(lineNumber, parts.toArray(String[]::new), true);
        }
        long nonEmpty = parts.stream().filter(p -> !p.isEmpty()).count();
        if (nonEmpty < 2) {
            return null;
        }
        return new Candidate(lineNumber, parts.toArray(String[]::new), false);
    }

    private void flush(List<ExtractedTable> tables, List<Candidate> region, int cols) {
        List<Candidate> content = region.stream().filter(c -> !c.separator()).toList();
        if (content.isEmpty() || cols < 2) {
            return;
        }
        // A3 续表切分：区域内出现与首行表头（normalize 口径）完全相同的行 → 断裂为新表段
        List<List<Candidate>> segments = new ArrayList<>();
        List<Candidate> segment = new ArrayList<>();
        List<String> baseHeader = null;
        for (Candidate candidate : content) {
            List<String> rowKey = List.of(candidate.cells());
            if (baseHeader != null && normalizeKey(rowKey).equals(normalizeKey(baseHeader))) {
                if (segment.size() >= 2) {
                    segments.add(segment);
                }
                segment = new ArrayList<>();
            }
            if (segment.isEmpty()) {
                baseHeader = rowKey;
            }
            segment.add(candidate);
        }
        if (segment.size() >= 2) {
            segments.add(segment);
        }
        for (List<Candidate> seg : segments) {
            buildTable(tables, seg, cols);
        }
    }

    private void buildTable(List<ExtractedTable> tables, List<Candidate> content, int cols) {
        List<String> header = List.of(content.get(0).cells());
        int tableIndex = tables.size();
        int[] continuationKey = null;
        for (ExtractedTable existing : tables) {
            if (normalizeKey(existing.header()).equals(normalizeKey(header))) {
                continuationKey = new int[]{existing.pageNumber(), existing.tableIndex()};
                tableIndex = existing.tableIndex() + 1;
                break;
            }
        }
        List<TableRowData> rows = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        if (continuationKey != null) {
            flags.add(TableDegrade.CONTINUATION_HEADER_SKIPPED);
        }
        for (int r = 1; r < content.size(); r++) {
            Candidate candidate = content.get(r);
            List<TableCell> cells = new ArrayList<>(cols);
            List<String> rowFlags = new ArrayList<>();
            boolean missingHere = false;
            for (int c = 0; c < cols; c++) {
                String value = c < candidate.cells().length ? TableTexts.cleanCell(candidate.cells()[c]) : null;
                if (value == null) {
                    missingHere = true;
                    cells.add(TableCell.missing(header.get(c)));
                } else {
                    cells.add(UnitTyping.typeOf(header.get(c), value));
                }
            }
            if (missingHere) {
                rowFlags.add(TableDegrade.COLUMN_MISALIGNMENT);
                flags.add(TableDegrade.COLUMN_MISALIGNMENT);
            }
            if (cells.stream().allMatch(TableCell::isMissing)) {
                continue;
            }
            String rowText = String.join(" ", cells.stream().map(TableCell::textOrEmpty).toList())
                    .replaceAll("\\s+", " ").strip();
            rows.add(new TableRowData(rows.size(), cells, rowText, candidate.lineNumber(), rowFlags));
        }
        tables.add(new ExtractedTable(1, tableIndex, header, rows, cols, flags, "HIGH",
                continuationKey, content.get(0).lineNumber(),
                content.get(content.size() - 1).lineNumber(), "ACTIVE"));
    }

    private String normalizeKey(List<String> header) {
        return header.stream().map(TableTexts::normalize).toList().toString();
    }
}
