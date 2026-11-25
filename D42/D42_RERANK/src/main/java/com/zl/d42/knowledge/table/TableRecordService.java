package com.zl.d42.knowledge.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.knowledge.document.ExtractedPage;
import com.zl.d42.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d42.knowledge.table.dto.TableExtractSummary;
import com.zl.d42.knowledge.table.dto.TableQueryHit;
import com.zl.d42.knowledge.table.dto.TableRowView;
import com.zl.d42.knowledge.table.dto.TableView;
import com.zl.d42.knowledge.table.model.ExtractedTable;
import com.zl.d42.knowledge.table.model.TableCell;
import com.zl.d42.knowledge.table.model.TableRowData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表格结构化记录编排服务（P1 核心；D1 裁决独立分支，不触碰页文本/状态机/错误码）。
 *
 * <p>入口 {@link #extractAndPersist}：按扩展名路由抽取器 → d42_table_extract/d42_table_row
 * 幂等落库（自然键 upsert）→ 双轨关联重算（D5：pageNumber∈page_numbers + 规范化行文本包含匹配）。
 * 任何表格分支内部异常都必须被导入钩子侧捕获降级，绝不阻断文档 COMPLETED（H1）。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.knowledge.table", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TableRecordService {

    private static final Logger log = LoggerFactory.getLogger(TableRecordService.class);

    private final TableRecordRepository repository;
    private final KnowledgeDocumentRepository documentRepository;
    private final TableProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PdfTableExtractor pdfTableExtractor = new PdfTableExtractor();
    private final TextTableExtractor textTableExtractor = new TextTableExtractor();
    private final HtmlTableExtractor htmlTableExtractor = new HtmlTableExtractor();
    private final OcrTablePort ocrTablePort = new DisabledOcrTablePort();

    public TableRecordService(TableRecordRepository repository,
                              KnowledgeDocumentRepository documentRepository,
                              TableProperties properties) {
        this.repository = repository;
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    public boolean isOcrEnabled() {
        return properties.getOcr().isEnabled();
    }

    // ------------------------------------------------------------------
    // 抽取入口（导入钩子 / 重抽取端点共用）
    // ------------------------------------------------------------------

    /**
     * 抽取并持久化。extension 为 inspect 已白名单校验的小写扩展名；
     * pages 仅 PDF 有值（既有解析链输出复用）；parsedText 非 PDF 文本源。
     *
     * <p>R02-04 语义（三态显式化，禁以 isEmpty 区分成败）：
     * ① 解析成功且有表格 → 事务内先清理本 documentId+version 全部旧表/旧行，再整体写入本次结果；
     * ② 解析成功且无表格 → 清理旧数据后空集提交（"成功为空"允许清空，任务书语义4）；
     * ③ 解析失败（异常/不支持扩展名）→ **不写不删**，返回 PARSE_FAILED，旧结构化记录完整保留；
     * 清理+写入+关联重算同处 @Transactional 事务，任何 DB 写失败整体回滚，不留半套数据。</p>
     */
    @Transactional
    public TableExtractSummary extractAndPersist(String documentId, String version, String extension,
                                                 byte[] content, List<ExtractedPage> pages,
                                                 String parsedText) {
        Integer numericVersion = toNumericVersion(documentId, version);
        if (numericVersion == null) {
            return new TableExtractSummary(documentId, version, 0, 0, 0, 0,
                    List.of(TableDegrade.VERSION_NOT_PERSISTABLE));
        }
        ParseOutcome outcome = route(extension, content, pages, parsedText);
        if (!outcome.parsed()) {
            log.warn("表格解析失败，保留既有结构化记录 documentId={} version={} reason={}",
                    documentId, version, outcome.reason());
            return new TableExtractSummary(documentId, version, 0, 0, 0, 0,
                    List.of(TableDegrade.PARSE_FAILED));
        }
        List<ExtractedTable> tables = outcome.tables();
        // R02-04 原子结果替换：先清本域旧数据（同事务），再整体写入（含成功为空 → 清空）
        repository.deleteByDocumentVersion(documentId, numericVersion);
        Map<String, Long> idByLocation = new HashMap<>();
        List<String> degraded = new ArrayList<>();
        int rowCount = 0;
        for (ExtractedTable table : tables) {
            Long continuationOfId = null;
            if (table.continuationKey() != null) {
                continuationOfId = idByLocation.get(locationKey(
                        table.continuationKey()[0], table.continuationKey()[1]));
            }
            String flags = String.join(",", new LinkedHashSet<>(table.detectFlags()));
            Long id = repository.upsertExtract(documentId, numericVersion, table.pageNumber(),
                    table.tableIndex(), table.headerSnapshotJsonArray(), table.rows().size(),
                    table.colCount(), table.sourceLineStart(), table.sourceLineEnd(),
                    flags, table.confidence(), continuationOfId, table.degradeStatus());
            idByLocation.put(locationKey(table.pageNumber(), table.tableIndex()), id);
            for (TableRowData row : table.rows()) {
                String cellsJson = cellsJson(table.header(), row.cells());
                repository.upsertRow(id, row.rowIndex(), cellsJson, row.sourceLineNumber(),
                        recordHash(documentId, version, table, row, cellsJson));
                rowCount++;
            }
            if ("DEGRADED".equals(table.degradeStatus())) {
                degraded.addAll(table.detectFlags());
            }
        }
        int[] linkage = recomputeLinkage(documentId, version);
        return new TableExtractSummary(documentId, version, tables.size(), rowCount,
                linkage[0], linkage[1], List.copyOf(degraded));
    }

    /**
     * R02-04：解析三态显式化。parsed=false（抽取异常/不支持扩展名）与
     * parsed=true + 空表集合（成功但无表格）严格区分——禁止以 List.isEmpty() 判定成败，
     * 防止失败被误当"无表格"而在原子替换时清空有效旧数据。
     */
    record ParseOutcome(boolean parsed, List<ExtractedTable> tables, String reason) {
        static ParseOutcome ok(List<ExtractedTable> tables) {
            return new ParseOutcome(true, tables, null);
        }

        static ParseOutcome failed(String reason) {
            return new ParseOutcome(false, List.of(), reason);
        }
    }

    private ParseOutcome route(String extension, byte[] content,
                               List<ExtractedPage> pages, String parsedText) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        try {
            switch (ext) {
                case "pdf" -> {
                    try (PDDocument document = Loader.loadPDF(content)) {
                        return ParseOutcome.ok(pdfTableExtractor.extract(document,
                                pages == null ? List.of() : pages));
                    }
                }
                case "txt", "md", "markdown" -> {
                    String text = parsedText != null ? parsedText
                            : new String(content, StandardCharsets.UTF_8);
                    return ParseOutcome.ok(textTableExtractor.extract(text));
                }
                case "html", "htm" -> {
                    String text = parsedText != null ? parsedText
                            : new String(content, StandardCharsets.UTF_8);
                    return ParseOutcome.ok(htmlTableExtractor.extract(text));
                }
                case "docx" -> {
                    // D3：Tika 文本轨既有、结构轨诚实退化（POI 接线 D42 候选）——属"成功+退化记录"非失败
                    return ParseOutcome.ok(List.of(new ExtractedTable(0, 0, List.of(), List.of(), 0,
                            Set.of(TableDegrade.DOCX_TABLE_NOT_STRUCTURED), "LOW",
                            null, null, null, "DEGRADED")));
                }
                default -> {
                    // 不支持/未知扩展名：保守失败态，不触碰旧数据（inspect 白名单外的防御路径）
                    return ParseOutcome.failed("UNSUPPORTED_EXTENSION:" + ext);
                }
            }
        } catch (Exception ex) {
            log.warn("表格分支抽取失败（不影响文档状态机）extension={} cause={}", ext, ex.toString());
            return ParseOutcome.failed(ext + ":" + ex);
        }
    }

    // ------------------------------------------------------------------
    // 双轨关联（D5/KP4：pageNumber ∈ page_numbers ∧ normalize(行文本) ⊆ normalize(chunk)）
    // ------------------------------------------------------------------

    /** @return int[]{命中表数, 零命中表数} */
    @Transactional
    public int[] recomputeLinkage(String documentId, String version) {
        Integer numericVersion = toNumericVersion(documentId, version);
        if (numericVersion == null) {
            return new int[]{0, 0};
        }
        List<Map<String, Object>> tables = repository.findTables(documentId, version, true);
        List<Map<String, Object>> chunks = repository.findChunksForLinkage(documentId, version);
        List<ChunkRow> chunkRows = chunks.stream().map(ChunkRow::of).toList();
        int hit = 0;
        int missing = 0;
        for (Map<String, Object> table : tables) {
            int pageNumber = ((Number) table.get("page_number")).intValue();
            long tableId = ((Number) table.get("id")).longValue();
            Set<String> flags = splitFlags((String) table.get("detect_flags"));
            if (pageNumber <= 0) {
                continue; // 退化记录（docx 等）无页概念，不参与关联
            }
            LinkedHashSet<String> hits = linkedChunkIds(tableId, pageNumber, chunkRows);
            if (hits.isEmpty()) {
                flags.add(TableDegrade.LINKAGE_MISSING);
                missing++;
            } else {
                flags.remove(TableDegrade.LINKAGE_MISSING);
                hit++;
            }
            // 预建表无 linked_chunk_ids_json 列：命中态持久化于 detect_flags，清单读面实时算
            repository.updateLinkageFlags(tableId, String.join(",", flags));
        }
        return new int[]{hit, missing};
    }

    /** 双轨关联判据（KP4 linkageRule）：pageNumber∈page_numbers ∧ normalize(行文本)⊆normalize(chunk)。 */
    private LinkedHashSet<String> linkedChunkIds(long tableId, int pageNumber,
                                                 List<ChunkRow> chunkRows) {
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Map<String, Object> row : repository.findRows(tableId)) {
            String rowText = normalizeRowText(readCells((String) row.get("cells_json")));
            if (rowText.isEmpty()) {
                continue;
            }
            for (ChunkRow chunk : chunkRows) {
                if (chunk.containsPage(pageNumber)
                        && TableTexts.normalize(chunk.content()).contains(rowText)) {
                    hits.add(chunk.chunkId());
                }
            }
        }
        return hits;
    }

    record ChunkRow(String chunkId, Set<Integer> pages, String content) {
        static ChunkRow of(Map<String, Object> row) {
            Set<Integer> pages = new LinkedHashSet<>();
            Object raw = row.get("page_numbers");
            if (raw != null) {
                for (String part : String.valueOf(raw).split(",")) {
                    String trimmed = part.strip();
                    if (!trimmed.isEmpty()) {
                        try {
                            pages.add(Integer.parseInt(trimmed));
                        } catch (NumberFormatException ignored) {
                            // 历史脏值忽略（对齐既有 splitPageNumbers 容错）
                        }
                    }
                }
            }
            Object contentValue = row.get("content");
            return new ChunkRow(String.valueOf(row.get("chunk_id")), pages,
                    contentValue == null ? "" : String.valueOf(contentValue));
        }

        boolean containsPage(int pageNumber) {
            return pages.contains(pageNumber);
        }
    }

    // ------------------------------------------------------------------
    // 重抽取端点语义（错误清单 REEXTRACT_TEXT_TRACK_ONLY）
    // ------------------------------------------------------------------

    /**
     * 重抽取（ADMIN 端点语义）：knowledge_document_content.content 既有原始字节列为数据源，
     * PDF/文本/HTML 全型可重跑；字节列缺失时退化仅重算关联（REEXTRACT 诚实标注，H8）。
     */
    @Transactional
    public TableExtractSummary reExtract(String documentId, String version, String fileName) {
        byte[] raw = repository.findRawContent(documentId);
        if (raw == null || raw.length == 0) {
            int[] linkage = recomputeLinkage(documentId, version);
            log.warn("重抽取无原始字节源，仅重算关联 documentId={}", documentId);
            return new TableExtractSummary(documentId, version, 0, 0,
                    linkage[0], linkage[1], List.of(TableDegrade.REEXTRACT_TEXT_TRACK_ONLY));
        }
        String extension = extensionOf(fileName);
        List<ExtractedPage> pages = documentRepository.findExtractedPages(documentId).stream()
                .map(page -> new ExtractedPage(page.pageNumber(), page.content(),
                        page.content() == null || page.content().isBlank()))
                .toList();
        return extractAndPersist(documentId, version, extension, raw, pages, null);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 读面（控制器鉴权后调用）
    // ------------------------------------------------------------------

    public List<TableView> listTables(String documentId, String version, boolean activeOnly) {
        List<Map<String, Object>> tables = repository.findTables(documentId, version, activeOnly);
        if (tables.isEmpty()) {
            return List.of();
        }
        String effectiveVersion = version == null || version.isBlank()
                ? String.valueOf(((Number) tables.get(0).get("version")).intValue()) : version;
        List<ChunkRow> chunkRows = repository.findChunksForLinkage(documentId, effectiveVersion)
                .stream().map(ChunkRow::of).toList();
        return tables.stream().map(row -> toView(row, chunkRows)).toList();
    }

    public Map<String, Object> tableRow(long tableId) {
        return repository.findTableById(tableId);
    }

    public List<TableRowView> listRows(long tableId) {
        Map<String, Object> table = repository.findTableById(tableId);
        List<String> header = table == null ? List.of()
                : readStringArray((String) table.get("header_snapshot_json"));
        return repository.findRows(tableId).stream().map(row -> new TableRowView(
                tableId,
                ((Number) row.get("row_index")).intValue(),
                fillHeaders(readCells((String) row.get("cells_json")), header),
                row.get("source_line_number") == null ? null
                        : ((Number) row.get("source_line_number")).intValue(),
                String.valueOf(row.get("record_hash")))).toList();
    }

    public List<TableQueryHit> queryByColumnValue(String documentId, String header, String value) {
        List<TableQueryHit> hits = new ArrayList<>();
        for (Map<String, Object> row : repository.queryByColumnValue(documentId, header, value)) {
            long tableId = ((Number) row.get("table_id")).longValue();
            Map<String, Object> table = repository.findTableById(tableId);
            List<String> tableHeader = table == null ? List.of()
                    : readStringArray((String) table.get("header_snapshot_json"));
            hits.add(new TableQueryHit(tableId,
                    String.valueOf(row.getOrDefault("documentId", "")),
                    table == null ? "" : String.valueOf(((Number) table.get("version")).intValue()),
                    ((Number) row.get("page_number")).intValue(),
                    ((Number) row.get("table_index")).intValue(),
                    ((Number) row.get("row_index")).intValue(),
                    fillHeaders(readCells((String) row.get("cells_json")), tableHeader)));
        }
        return hits;
    }

    /** 同步绑定清理（D7：删除闭环/UPDATE_CLEANUP 既有 MySQL 事务内追加，幂等）。 */
    public int deleteForDocument(String documentId) {
        return repository.deleteByDocument(documentId);
    }

    public int deleteForDocumentVersion(String documentId, String version) {
        Integer numericVersion = toNumericVersion(documentId, version);
        return numericVersion == null ? 0 : repository.deleteByDocumentVersion(documentId, numericVersion);
    }

    /** TABLE_ORPHAN 检出（P2 对账追加，H4 只检测修复走管线）。 */
    public List<Map<String, Object>> detectOrphanRecords() {
        return repository.findOrphanRecords();
    }

    public int countFixtureRecords(String prefix) {
        return repository.countByDocumentPrefix(prefix);
    }

    // ------------------------------------------------------------------
    // 序列化辅助（KP4 cellSchema：{"h","v","t","u"}，无 Map 出参）
    // ------------------------------------------------------------------

    private String cellsJson(List<String> header, List<TableCell> cells) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            TableCell cell = cells.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{')
                    .append("\"h\":").append(ExtractedTable.escape(
                            cell.header() != null ? cell.header()
                                    : (i < header.size() ? header.get(i) : "col_" + i)))
                    .append(",\"v\":").append(cell.value() == null ? "null" : ExtractedTable.escape(cell.value()))
                    .append(",\"t\":").append(ExtractedTable.escape(cell.type()))
                    .append(",\"u\":").append(cell.unit() == null ? "null" : ExtractedTable.escape(cell.unit()))
                    .append('}');
        }
        return builder.append(']').toString();
    }

    List<TableCell> readCells(String json) {
        List<TableCell> cells = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return cells;
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            for (JsonNode node : array) {
                cells.add(new TableCell(
                        node.path("h").asText(null),
                        node.hasNonNull("v") ? node.get("v").asText() : null,
                        node.path("t").asText("string"),
                        node.hasNonNull("u") ? node.get("u").asText() : null));
            }
        } catch (Exception ex) {
            log.warn("cells_json 解析失败（读面容错）：{}", ex.toString());
        }
        return cells;
    }

    private List<TableCell> fillHeaders(List<TableCell> cells, List<String> header) {
        List<TableCell> filled = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            TableCell cell = cells.get(i);
            String h = cell.header() != null && !cell.header().isBlank() ? cell.header()
                    : (i < header.size() ? header.get(i) : "col_" + i);
            filled.add(new TableCell(h, cell.value(), cell.type(), cell.unit()));
        }
        return filled;
    }

    private List<String> readStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                values.add(node.asText());
            }
            return values;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String recordHash(String documentId, String version, ExtractedTable table,
                              TableRowData row, String cellsJson) {
        try {
            String raw = documentId + "|" + version + "|" + table.pageNumber() + "|"
                    + table.tableIndex() + "|" + row.rowIndex() + "|" + cellsJson;
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizeRowText(List<TableCell> cells) {
        StringBuilder builder = new StringBuilder();
        for (TableCell cell : cells) {
            if (!cell.isMissing()) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(cell.value());
            }
        }
        return TableTexts.normalize(builder.toString());
    }

    private Integer toNumericVersion(String documentId, String version) {
        if (version == null || !version.strip().matches("\\d+")) {
            log.warn("版本非数字串，表格记录不落既有 int 版本列（KP2 适配，documentId={} version={}）",
                    documentId, version);
            return null;
        }
        try {
            return Integer.valueOf(version.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Set<String> splitFlags(String value) {
        Set<String> flags = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String trimmed = part.strip();
                if (!trimmed.isEmpty()) {
                    flags.add(trimmed);
                }
            }
        }
        return flags;
    }

    private TableView toView(Map<String, Object> row, List<ChunkRow> chunkRows) {
        long tableId = ((Number) row.get("id")).longValue();
        int pageNumber = ((Number) row.get("page_number")).intValue();
        List<String> linked = pageNumber <= 0 ? List.of()
                : new ArrayList<>(linkedChunkIds(tableId, pageNumber, chunkRows));
        return new TableView(tableId,
                String.valueOf(row.get("document_id")),
                String.valueOf(((Number) row.get("version")).intValue()),
                pageNumber,
                ((Number) row.get("table_index")).intValue(),
                readStringArray((String) row.get("header_snapshot_json")),
                ((Number) row.get("row_count")).intValue(),
                ((Number) row.get("col_count")).intValue(),
                row.get("source_line_start") == null ? null : ((Number) row.get("source_line_start")).intValue(),
                row.get("source_line_end") == null ? null : ((Number) row.get("source_line_end")).intValue(),
                linked,
                new ArrayList<>(splitFlags((String) row.get("detect_flags"))),
                String.valueOf(row.get("confidence")),
                row.get("continuation_of_table_id") == null ? null
                        : ((Number) row.get("continuation_of_table_id")).longValue(),
                String.valueOf(row.get("status")));
    }

    private String locationKey(int page, int index) {
        return page + ":" + index;
    }
}
