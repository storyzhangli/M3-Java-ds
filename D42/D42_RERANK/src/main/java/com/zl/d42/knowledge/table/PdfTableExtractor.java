package com.zl.d42.knowledge.table;

import com.zl.d42.knowledge.document.ExtractedPage;
import com.zl.d42.knowledge.table.model.ExtractedTable;
import com.zl.d42.knowledge.table.model.TableCell;
import com.zl.d42.knowledge.table.model.TableRowData;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PDF 表格坐标抽取器（D1/D2 裁决主路径；KP4 冻结算法，只用 PDFBox 3.0.7 TextPosition 坐标能力，
 * 禁表格识别新框架 H3；不修改页文本，与正文解析完全解耦）。
 *
 * <p>算法链：自定义 Stripper 子类按物理页收集 TextPosition(x/y/width/fontSize) →
 * 行聚类（y 差 &lt; 行锚 fontSize×0.5）→ 行内 x 间隙 &gt; 24pt 切单元格 →
 * 连续 ≥2 行且每行 ≥2 单元 = 表格区域（单列散文本/标题不误判）→ 区域 x 起点 24pt 聚列锚 →
 * 表头=区域首行（fontSize 中位数差异为强信号）→ 按列桶映射 + null 占位（不串列）→
 * 合并覆盖（cell 右缘越过右列锚且右桶无独立起点 = MERGED_CELL）→
 * 列距中位数 &lt; 60pt = LOW_CONFIDENCE → 跨页续表（表头快照 normalize 匹配，表头不入数据行）→
 * 空白+含图页 = IMAGE_TABLE_NOT_PARSED 退化记录（OCR 无引擎，D4）。</p>
 */
public final class PdfTableExtractor {

    static final double COL_GAP_PT = 24.0;
    static final double ROW_TOLERANCE_FACTOR = 0.5;
    static final double LOW_CONFIDENCE_SPACING_PT = 60.0;

    /** 坐标采样（KP1 探针同口径：getXDirAdj/getYDirAdj/getWidthDirAdj/getFontSize）。 */
    record Pos(double x, double y, double width, double fontSize, String text) {
    }

    record CellRun(double x, double end, String text, double fontSize) {
    }

    /** 每页抽取输入：行序列 + 区域切分基础统计。 */
    private record PagePositions(int pageNumber, List<List<Pos>> rows, boolean blankWithImage) {
    }

    /**
     * 抽取全部页面表格。pages 为既有解析链输出（页码/blank 判定复用，不重新解析文本语义）。
     */
    public List<ExtractedTable> extract(PDDocument document, List<ExtractedPage> pages) throws IOException {
        List<ExtractedTable> tables = new ArrayList<>();
        for (ExtractedPage page : pages) {
            int pageNumber = page.pageNumber();
            List<List<Pos>> rows = collectRows(document, pageNumber);
            if (rows.isEmpty()) {
                if (page.blankPage() && pageHasImage(document, pageNumber)) {
                    tables.add(new ExtractedTable(pageNumber, 0, List.of(), List.of(), 0,
                            Set.of(TableDegrade.IMAGE_TABLE_NOT_PARSED), "LOW",
                            null, null, null, "DEGRADED"));
                }
                continue;
            }
            tables.addAll(extractPageTables(pageNumber, rows));
        }
        return linkContinuations(tables);
    }

    /** 收集指定页文本位置并按 KP4 行聚类（y 升序，行内 x 升序）。 */
    private List<List<Pos>> collectRows(PDDocument document, int pageNumber) throws IOException {
        List<Pos> all = new ArrayList<>();
        PDFTextStripper collector = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition tp) {
                String unicode = tp.getUnicode();
                if (unicode != null && !unicode.isEmpty()) {
                    all.add(new Pos(tp.getXDirAdj(), tp.getYDirAdj(),
                            tp.getWidthDirAdj(), tp.getFontSize(), unicode));
                }
                super.processTextPosition(tp);
            }

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                // 坐标收集已在 processTextPosition 完成；此处抑制默认输出（仅采集不回写）
            }
        };
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        collector.setLineSeparator("\n");
        collector.getText(document);
        if (all.isEmpty()) {
            return List.of();
        }
        all.sort(Comparator.comparingDouble(Pos::y).thenComparingDouble(Pos::x));
        List<List<Pos>> rows = new ArrayList<>();
        List<Pos> current = new ArrayList<>();
        double anchorY = Double.NaN;
        double anchorFs = Double.NaN;
        for (Pos pos : all) {
            if (current.isEmpty()) {
                current.add(pos);
                anchorY = pos.y();
                anchorFs = pos.fontSize();
                continue;
            }
            double tolerance = Math.max(anchorFs, pos.fontSize()) * ROW_TOLERANCE_FACTOR;
            if (Math.abs(pos.y() - anchorY) <= tolerance) {
                current.add(pos);
            } else {
                rows.add(new ArrayList<>(current));
                current.clear();
                current.add(pos);
                anchorY = pos.y();
                anchorFs = pos.fontSize();
            }
        }
        rows.add(new ArrayList<>(current));
        rows.sort(Comparator.comparingDouble(r -> r.get(0).y()));
        for (List<Pos> row : rows) {
            row.sort(Comparator.comparingDouble(Pos::x));
        }
        return rows;
    }

    private boolean pageHasImage(PDDocument document, int pageNumber) {
        try {
            PDPage page = document.getPage(pageNumber - 1);
            PDResources resources = page.getResources();
            if (resources == null) {
                return false;
            }
            for (COSName name : resources.getXObjectNames()) {
                if (resources.isImageXObject(name)) {
                    return true;
                }
            }
        } catch (RuntimeException ex) {
            // pdfbox 3.x：getXObjectNames/isImageXObject 不抛 IOException，异常面为运行时
            return false;
        }
        return false;
    }

    /** 单页区域切分 + 表格构建（行序列 → 连续 ≥2 单元格行 = 区域）。
     *  R01 修复（对账实证）：区域起始前的单 cell 行若按半间隙重切可得 ≥2 单元
     *  （宽表头字距小、24pt 间隙并成一段的常见形态），吸收为首行表头；
     *  纯连续中文标题行重切仍为 1 段，不误吸。 */
    private List<ExtractedTable> extractPageTables(int pageNumber, List<List<Pos>> rows) {
        List<ExtractedTable> tables = new ArrayList<>();
        List<List<CellRun>> region = new ArrayList<>();
        int tableIndexOnPage = 0;
        int regionStartRow = -1;
        List<Pos> pendingRow = null;
        int pendingIndex = -1;
        for (int i = 0; i <= rows.size(); i++) {
            List<Pos> row = i < rows.size() ? rows.get(i) : List.of();
            List<CellRun> cells = row.isEmpty() ? List.of() : splitCells(row, COL_GAP_PT);
            boolean qualifies = cells.size() >= 2;
            if (qualifies) {
                if (region.isEmpty()) {
                    if (pendingRow != null) {
                        List<CellRun> retry = splitCells(pendingRow, COL_GAP_PT / 2);
                        if (retry.size() >= 2) {
                            region.add(retry);
                            regionStartRow = pendingIndex + 1;
                        }
                    }
                    if (region.isEmpty()) {
                        regionStartRow = i + 1;
                    }
                }
                region.add(cells);
                pendingRow = null;
                pendingIndex = -1;
            } else {
                if (region.size() >= 2) {
                    tables.add(buildTable(pageNumber, tableIndexOnPage++, region, regionStartRow, i));
                }
                region = new ArrayList<>();
                pendingRow = cells.size() == 1 ? row : null;
                pendingIndex = cells.size() == 1 ? i : -1;
            }
        }
        return tables;
    }

    /** 行内按 x 间隙 &gt; gap 切单元段（KP4 单元格切分口径；表头恢复用半间隙调用）。 */
    private List<CellRun> splitCells(List<Pos> row) {
        return splitCells(row, COL_GAP_PT);
    }

    private List<CellRun> splitCells(List<Pos> row, double gap) {
        List<CellRun> cells = new ArrayList<>();
        double prevEnd = Double.NEGATIVE_INFINITY;
        StringBuilder text = new StringBuilder();
        double startX = 0;
        double end = 0;
        double fs = row.get(0).fontSize();
        for (Pos pos : row) {
            if (text.length() > 0 && pos.x() - prevEnd > gap) {
                cells.add(new CellRun(startX, end, text.toString(), fs));
                text = new StringBuilder();
                startX = pos.x();
                fs = pos.fontSize();
            }
            if (text.length() == 0) {
                startX = pos.x();
                fs = pos.fontSize();
            }
            text.append(pos.text());
            end = Math.max(end, pos.x() + pos.width());
            prevEnd = pos.x() + pos.width();
        }
        if (text.length() > 0) {
            cells.add(new CellRun(startX, end, text.toString(), fs));
        }
        return cells;
    }

    /** 区域 → 表格：列锚聚类、表头、类型化、合并/错位检出、置信度。 */
    private ExtractedTable buildTable(int pageNumber, int tableIndex,
                                      List<List<CellRun>> region, int startRow, int endRowExclusive) {
        // R02-02 列锚=至少 2 行支持的起点簇：单次出现的错位起点不再成"伪列"，
        // 该 run 转为 surplus 由 assignColumns 偏移/EXTRA 处置（R01 直接聚入会产生虚假列结构）。
        List<Double> clusterReps = new ArrayList<>();
        List<Set<Integer>> clusterSupport = new ArrayList<>();
        for (int r = 0; r < region.size(); r++) {
            for (CellRun cell : region.get(r)) {
                int target = -1;
                for (int c = 0; c < clusterReps.size(); c++) {
                    if (Math.abs(cell.x() - clusterReps.get(c)) <= COL_GAP_PT) {
                        target = c;
                        break;
                    }
                }
                if (target < 0) {
                    clusterReps.add(cell.x());
                    clusterSupport.add(new LinkedHashSet<>());
                    target = clusterReps.size() - 1;
                }
                clusterSupport.get(target).add(r);
            }
        }
        List<Double> anchors = new ArrayList<>();
        for (int c = 0; c < clusterReps.size(); c++) {
            if (clusterSupport.get(c).size() >= 2) {
                anchors.add(clusterReps.get(c));
            }
        }
        if (anchors.size() < 2) {
            // 两行区域/极端稀疏表兜底：全簇为锚（维持 R01 口径，宁多判不错构）
            anchors = new ArrayList<>(clusterReps);
            anchors.sort(Comparator.naturalOrder());
        }
        int colCount = anchors.size();
        List<Double> spacings = new ArrayList<>();
        for (int i = 1; i < anchors.size(); i++) {
            spacings.add(anchors.get(i) - anchors.get(i - 1));
        }
        double medianSpacing = median(spacings);

        // 表头=区域首行
        List<CellRun> headerRow = region.get(0);
        List<String> header = assignHeader(headerRow, anchors, colCount);

        List<TableRowData> dataRows = new ArrayList<>();
        Set<String> tableFlags = new LinkedHashSet<>();
        int anomalyRows = 0;
        for (int r = 1; r < region.size(); r++) {
            List<CellRun> row = region.get(r);
            ColumnAssignment assignment = assignColumns(row, anchors, colCount);
            List<List<CellRun>> buckets = assignment.buckets();
            List<TableCell> cells = new ArrayList<>(colCount);
            List<String> rowFlags = new ArrayList<>(assignment.flags());
            boolean mergedHere = false;
            boolean missingHere = false;
            for (int c = 0; c < colCount; c++) {
                if (buckets.get(c).isEmpty()) {
                    // 右邻缺失：检查左邻单元是否覆盖本列锚（合并）
                    double anchorX = anchors.get(c);
                    if (c > 0 && !buckets.get(c - 1).isEmpty()) {
                        CellRun left = last(buckets.get(c - 1));
                        if (left.end() > anchorX + 2.0) {
                            mergedHere = true;
                            cells.add(TableCell.missing(header.get(c)));
                            continue;
                        }
                    }
                    missingHere = true;
                    cells.add(TableCell.missing(header.get(c)));
                } else {
                    String text = joinBucketText(buckets.get(c));
                    cells.add(UnitTyping.typeOf(header.get(c), TableTexts.cleanCell(text)));
                }
            }
            if (mergedHere) {
                rowFlags.add(TableDegrade.MERGED_CELL);
            }
            if (missingHere) {
                rowFlags.add(TableDegrade.COLUMN_MISALIGNMENT);
            }
            // R02-02：无法可靠映射的独立剩余单元格——不并入任何列值，原文按 EXTRA_COL_* 追加保留
            int extraIndex = 1;
            for (CellRun extra : assignment.extraCells()) {
                cells.add(new TableCell("EXTRA_COL_" + extraIndex,
                        TableTexts.cleanCell(extra.text()), "string", null));
                extraIndex++;
            }
            if (!rowFlags.isEmpty()) {
                anomalyRows++;
                tableFlags.addAll(rowFlags);
            }
            boolean allNull = cells.stream().allMatch(TableCell::isMissing);
            if (allNull) {
                continue; // 全 null 行不产生记录（D10/A4）
            }
            String rowText = String.join(" ", cells.stream().map(TableCell::textOrEmpty).toList())
                    .replaceAll("\\s+", " ").strip();
            dataRows.add(new TableRowData(dataRows.size(), List.copyOf(cells), rowText,
                    startRow + r, List.copyOf(rowFlags)));
        }
        if (medianSpacing > 0 && medianSpacing < LOW_CONFIDENCE_SPACING_PT) {
            tableFlags.add(TableDegrade.LOW_CONFIDENCE);
        }
        String confidence = tableFlags.contains(TableDegrade.LOW_CONFIDENCE) ? "LOW" : "HIGH";
        return new ExtractedTable(pageNumber, tableIndex, List.copyOf(header),
                List.copyOf(dataRows), colCount, tableFlags, confidence,
                null, startRow, endRowExclusive - 1, "ACTIVE");
    }

    /**
     * R02-02 列归属校验：三阶段取代 R01 的"最近锚+桶内全拼接"。
     * ① nearest 入桶（R01 口径）；② 桶内 surplus 优先再分配给空锚（修复他列留空时的静默合并/错捕）；
     * ③ 各桶主元=距锚最近 run，其余 run 区分"列内文本片段"（legitimate 拼接，保留 R01 正常表格语义）
     * 与"独立剩余单元格"（越过列横跨度或异常大间隙→EXTRA_CELL，**不静默拼接**，原文保留）；
     * 主元距锚偏差 &gt; 列间隙阈值 → COLUMN_MISALIGNMENT（横向偏移不猜归属，原文保留）。
     */
    private record ColumnAssignment(List<List<CellRun>> buckets, List<CellRun> extraCells,
                                    Set<String> flags) {
    }

    private ColumnAssignment assignColumns(List<CellRun> row, List<Double> anchors, int colCount) {
        List<List<CellRun>> buckets = new ArrayList<>(colCount);
        for (int i = 0; i < colCount; i++) {
            buckets.add(new ArrayList<>());
        }
        for (CellRun cell : row) {
            buckets.get(nearestAnchor(anchors, cell.x())).add(cell);
        }
        // 阶段②：surplus run 若更贴近某空锚（距离 ≤ 列间隙阈值）→ 再分配
        boolean moved = true;
        while (moved) {
            moved = false;
            outer:
            for (int b = 0; b < colCount; b++) {
                List<CellRun> runs = buckets.get(b);
                for (int i = 0; i < runs.size(); i++) {
                    CellRun run = runs.get(i);
                    if (Math.abs(run.x() - anchors.get(b)) <= COL_GAP_PT) {
                        continue; // 本身在列跨度内，非错捕
                    }
                    int target = -1;
                    double targetDiff = Double.MAX_VALUE;
                    for (int e = 0; e < colCount; e++) {
                        if (buckets.get(e).isEmpty()) {
                            double diff = Math.abs(run.x() - anchors.get(e));
                            if (diff < targetDiff) {
                                targetDiff = diff;
                                target = e;
                            }
                        }
                    }
                    if (target >= 0 && targetDiff < Math.abs(run.x() - anchors.get(b))) {
                        runs.remove(i);
                        buckets.get(target).add(run);
                        buckets.get(target).sort(Comparator.comparingDouble(CellRun::x));
                        moved = true;
                        break outer;
                    }
                }
            }
        }
        // 阶段③：主元选择 + 片段/独立单元格区分
        Set<String> flags = new LinkedHashSet<>();
        List<CellRun> extraCells = new ArrayList<>();
        for (int b = 0; b < colCount; b++) {
            List<CellRun> runs = buckets.get(b);
            if (runs.isEmpty()) {
                continue;
            }
            int primaryIdx = 0;
            double primaryDiff = Math.abs(runs.get(0).x() - anchors.get(b));
            for (int i = 1; i < runs.size(); i++) {
                double diff = Math.abs(runs.get(i).x() - anchors.get(b));
                if (diff < primaryDiff) {
                    primaryDiff = diff;
                    primaryIdx = i;
                }
            }
            if (primaryDiff > COL_GAP_PT) {
                flags.add(TableDegrade.COLUMN_MISALIGNMENT);
            }
            List<CellRun> primaryList = new ArrayList<>();
            primaryList.add(runs.remove(primaryIdx));
            for (CellRun run : runs) { // 剩余按 x 升序（入桶序）
                double nextAnchor = b + 1 < colCount ? anchors.get(b + 1) : Double.MAX_VALUE;
                double prevEnd = primaryList.get(primaryList.size() - 1).end();
                boolean crossesSpan = run.x() >= nextAnchor - 4.0;
                boolean wideGap = run.x() - prevEnd > LOW_CONFIDENCE_SPACING_PT;
                if (crossesSpan || wideGap) {
                    extraCells.add(run);
                    flags.add(TableDegrade.EXTRA_CELL);
                } else {
                    primaryList.add(run); // 列内文本片段：保持拼接（正常单元格长文本形态）
                }
            }
            buckets.set(b, primaryList);
        }
        return new ColumnAssignment(buckets, extraCells, flags);
    }

    private int nearestAnchor(List<Double> anchors, double x) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            double diff = Math.abs(x - anchors.get(i));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private List<String> assignHeader(List<CellRun> headerRow, List<Double> anchors, int colCount) {
        List<String> header = new ArrayList<>();
        List<List<CellRun>> buckets = assignColumns(headerRow, anchors, colCount).buckets();
        for (int i = 0; i < colCount; i++) {
            // 真实手册暴露（R02-01）：空白/无映射表头桶 cleanCell→null，
            // List.copyOf 不容 null；空表头列统一 col_i 占位（与文本侧异常组占位一致）
            String text = buckets.get(i).isEmpty() ? null : joinBucketText(buckets.get(i));
            header.add(text == null ? "col_" + i : text);
        }
        return header;
    }

    private String joinBucketText(List<CellRun> bucket) {
        StringBuilder text = new StringBuilder();
        for (CellRun run : bucket) {
            text.append(run.text());
        }
        return TableTexts.cleanCell(text.toString());
    }

    private CellRun last(List<CellRun> list) {
        return list.get(list.size() - 1);
    }

    /**
     * 跨页续表（KP4 continuationRule）：后续区域首行表头 normalize 序列与前面某表快照一致 →
     * 判续表：CONTINUATION_HEADER_SKIPPED（表头本就不入数据行）+ continuationKey 指向前表。
     */
    private List<ExtractedTable> linkContinuations(List<ExtractedTable> tables) {
        List<ExtractedTable> linked = new ArrayList<>();
        record Prev(int page, int index, String key) {
        }
        List<Prev> history = new ArrayList<>();
        for (ExtractedTable table : tables) {
            if (!"ACTIVE".equals(table.degradeStatus()) || table.header().isEmpty()) {
                linked.add(table);
                continue;
            }
            String key = normalizeHeaderKey(table);
            Prev prev = null;
            for (Prev candidate : history) {
                if (candidate.key().equals(key) && !(candidate.page() == table.pageNumber()
                        && candidate.index() == table.tableIndex())) {
                    prev = candidate;
                }
            }
            Set<String> flags = new LinkedHashSet<>(table.detectFlags());
            if (prev != null) {
                flags.add(TableDegrade.CONTINUATION_HEADER_SKIPPED);
                linked.add(new ExtractedTable(table.pageNumber(), table.tableIndex(),
                        table.header(), table.rows(), table.colCount(), flags,
                        table.confidence(), new int[]{prev.page(), prev.index()},
                        table.sourceLineStart(), table.sourceLineEnd(), table.degradeStatus()));
            } else {
                linked.add(new ExtractedTable(table.pageNumber(), table.tableIndex(),
                        table.header(), table.rows(), table.colCount(), flags,
                        table.confidence(), null,
                        table.sourceLineStart(), table.sourceLineEnd(), table.degradeStatus()));
                history.add(new Prev(table.pageNumber(), table.tableIndex(), key));
            }
        }
        return linked;
    }

    private String normalizeHeaderKey(ExtractedTable table) {
        return table.header().stream().map(TableTexts::normalize).toList().toString().toLowerCase(Locale.ROOT);
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    /** 空 Writer 桩：getText 需要 writer，输出丢弃（坐标收集在 processTextPosition）。 */
    private static final Writer NULL_WRITER = new Writer() {
        @Override
        public void write(char[] cbuf, int off, int len) {
            // no-op
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    };
}
