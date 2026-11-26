package com.zl.d43.knowledge.table;

import com.zl.d43.knowledge.table.TableDegrade;
import com.zl.d43.knowledge.table.model.ExtractedTable;
import com.zl.d43.knowledge.table.model.TableCell;
import com.zl.d43.knowledge.table.model.TableRowData;

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
 * 空单元格 null 占位不串列；全 null 行不产生记录。</p>
 *
 * <p>R02-03 整改：列数不一致行不再"断裂丢弃"。两类形态分治——
 * ① 换表探测：候选错位行与其后首个内容行列数一致 → 判"新表切换"，按新列数成区（R01 断裂语义保留）；
 * ② 孤立错位行：不并入正常记录、不丢弃，进入当前区域异常收集器；flush 时输出独立
 *   MISALIGNED_LINE_GROUP 异常表记录（DEGRADED，不污染正常表、不参与续表/双轨/精确检索），
 *   每行保留原始文本映射 cells（缺列 null 占位 / 超列 EXTRA_COL_* 原文）、源行号、
 *   行级 EXPECTED_COLS_n_ACTUAL_m 信息（并入表级 detect_flags 可经 GET /tables 查询）。</p>
 */
public final class TextTableExtractor {

    private static final Pattern SEPARATOR_CELL = Pattern.compile("^:?-{2,}:?$");

    /** 异常组表 tableIndex 保留段：与正常/续表 index 空间（0..N）彻底隔离，避免自然键冲突。 */
    static final int MISALIGNED_INDEX_BASE = 10_000;

    record Candidate(int lineNumber, String[] cells, boolean separator) {
    }

    public List<ExtractedTable> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\\R");
        List<ExtractedTable> tables = new ArrayList<>();
        List<Candidate> region = new ArrayList<>();
        List<Candidate> misaligned = new ArrayList<>();
        int expectedCols = -1;
        for (int i = 0; i <= lines.length; i++) {
            Candidate candidate = i < lines.length ? classify(lines[i], i + 1) : null;
            if (candidate != null) {
                if (!candidate.separator() && expectedCols != -1
                        && candidate.cells().length != expectedCols) {
                    if (switchProbe(lines, i, candidate)) {
                        // 新列数持续 ≥2 行：判换表，断裂成新区（该行为新表首行=表头，形态合法）
                        flush(tables, region, misaligned, expectedCols);
                        region = new ArrayList<>();
                        misaligned = new ArrayList<>();
                        expectedCols = -1;
                    } else {
                        misaligned.add(candidate); // R02-03：孤立错位行记录在案，不无痕消失
                        continue;
                    }
                }
                if (!candidate.separator() && expectedCols == -1) {
                    expectedCols = candidate.cells().length;
                }
                region.add(candidate);
            } else {
                flush(tables, region, misaligned, expectedCols);
                region = new ArrayList<>();
                misaligned = new ArrayList<>();
                expectedCols = -1;
            }
        }
        return tables;
    }

    /** 换表探测：候选行之后首个内容行（跳过分隔行/边界判定）列数一致 → 新表切换成立。 */
    private boolean switchProbe(String[] lines, int index, Candidate candidate) {
        for (int j = index + 1; j < lines.length; j++) {
            Candidate next = classify(lines[j], j + 1);
            if (next == null) {
                return false; // 区域边界：其后无内容行
            }
            if (next.separator()) {
                continue;
            }
            return next.cells().length == candidate.cells().length;
        }
        return false;
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

    private void flush(List<ExtractedTable> tables, List<Candidate> region,
                       List<Candidate> misaligned, int cols) {
        List<Candidate> content = region.stream().filter(c -> !c.separator()).toList();
        List<String> anchorHeader = null;
        if (!content.isEmpty() && cols >= 2) {
            // A3 续表切分：区域内出现与首行表头（normalize 口径）完全相同的行 → 断裂为新表段
            anchorHeader = List.of(content.get(0).cells());
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
        if (!misaligned.isEmpty()) {
            buildMisalignedTable(tables, misaligned, cols, anchorHeader);
        }
    }

    private void buildTable(List<ExtractedTable> tables, List<Candidate> content, int cols) {
        List<String> header = List.of(content.get(0).cells());
        int tableIndex = tables.size();
        int[] continuationKey = null;
        for (ExtractedTable existing : tables) {
            if ("DEGRADED".equals(existing.degradeStatus())) {
                continue; // R02：异常组表不参与续表匹配
            }
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

    /** R02-03：异常组表——错位行原样保留（缺列 null 占位、超列 EXTRA_COL_* 原文、行号与列数信息可追溯）。 */
    private void buildMisalignedTable(List<ExtractedTable> tables, List<Candidate> misaligned,
                                      int cols, List<String> baseHeader) {
        int maxCols = misaligned.stream().mapToInt(c -> c.cells().length).max().orElse(cols);
        int width = Math.max(cols, baseHeader == null ? maxCols : baseHeader.size());
        List<String> header = baseHeader != null && baseHeader.size() >= cols && width == cols
                ? baseHeader
                : java.util.stream.IntStream.range(0, width).mapToObj(i -> "col_" + i).toList();
        List<TableRowData> rows = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        flags.add(TableDegrade.MISALIGNED_LINE_GROUP);
        for (Candidate candidate : misaligned) {
            List<TableCell> cells = new ArrayList<>(width);
            List<String> rowFlags = new ArrayList<>(List.of(
                    TableDegrade.COLUMN_MISALIGNMENT,
                    "EXPECTED_COLS_" + cols + "_ACTUAL_" + candidate.cells().length));
            flags.addAll(rowFlags);
            for (int c = 0; c < width; c++) {
                String value = c < candidate.cells().length
                        ? TableTexts.cleanCell(candidate.cells()[c]) : null;
                cells.add(value == null || value.isEmpty()
                        ? TableCell.missing(header.get(c)) : UnitTyping.typeOf(header.get(c), value));
            }
            int extraIndex = 1;
            for (int c = width; c < candidate.cells().length; c++) { // 超列值不丢弃：原文保留
                String extra = TableTexts.cleanCell(candidate.cells()[c]);
                cells.add(new TableCell("EXTRA_COL_" + extraIndex++,
                        extra == null || extra.isEmpty() ? null : extra, "string", null));
            }
            if (cells.stream().allMatch(TableCell::isMissing)) {
                continue;
            }
            String rowText = String.join(" ", cells.stream().map(TableCell::textOrEmpty).toList())
                    .replaceAll("\\s+", " ").strip();
            rows.add(new TableRowData(rows.size(), cells, rowText, candidate.lineNumber(), rowFlags));
        }
        if (rows.isEmpty()) {
            return;
        }
        tables.add(new ExtractedTable(1, MISALIGNED_INDEX_BASE + tables.size(), header, rows,
                width, flags, "LOW", null,
                rows.get(0).sourceLineNumber(),
                rows.get(rows.size() - 1).sourceLineNumber(), "DEGRADED"));
    }

    private String normalizeKey(List<String> header) {
        return header.stream().map(TableTexts::normalize).toList().toString();
    }
}
