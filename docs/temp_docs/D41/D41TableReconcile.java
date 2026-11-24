import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D41 源表逐格对账器（任务书 §5：抽取结果 vs d41-fixture-truth.json 逐格比对，diff 清单落盘）。
 *
 * <p>口径：isHeader→表头快照比对；数据格→v/t/u 严格比对（expectType=merged 特判 MERGED_CELL 旗标）；
 * 网格内 truth 缺项→期望缺失格（value=null, type=null）且表级 COLUMN_MISALIGNMENT；
 * row=-1 负项→该文本禁止出现在任何数据格。a3 续表附加 continuationOfTableId 指向主表断言。</p>
 *
 * <p>用法：java -cp &lt;cp.txt&gt; D41TableReconcile.java --base URL --token T --truth f --docmap f --out f</p>
 */
public class D41TableReconcile {

    static final ObjectMapper M = new ObjectMapper();
    static final List<String> DIFFS = new ArrayList<>();
    static int compared = 0;
    static HttpClient client;
    static String base;
    static String token;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        base = opt.get("base");
        token = opt.get("token");
        client = HttpClient.newHttpClient();

        JsonNode truth = M.readTree(Files.readString(Path.of(opt.get("truth")), StandardCharsets.UTF_8));
        Map<String, String> docMap = new LinkedHashMap<>();
        JsonNode docNode = M.readTree(Files.readString(Path.of(opt.get("docmap")), StandardCharsets.UTF_8));
        docNode.fields().forEachRemaining(e -> docMap.put(e.getKey(), e.getValue().asText()));

        // 按 (file, tableId) 分组
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode cell : truth.get("cells")) {
            groups.computeIfAbsent(cell.get("file").asText() + "::" + cell.get("tableId").asText(),
                    k -> new ArrayList<>()).add(cell);
        }

        for (Map.Entry<String, List<JsonNode>> entry : groups.entrySet()) {
            String file = entry.getKey().substring(0, entry.getKey().indexOf("::"));
            String tableId = entry.getKey().substring(entry.getKey().indexOf("::") + 2);
            List<JsonNode> cells = entry.getValue();
            String docId = docMap.get(file);
            if (docId == null) {
                diff(tableId, "MISSING_DOC", file);
                continue;
            }
            reconcileTable(tableId, docId, cells);
        }

        // 专项：a5 第二页图像退化记录
        String a5 = docMap.getOrDefault("d41-fx-anomaly-a5.pdf", "");
        if (!a5.isEmpty()) {
            JsonNode tables = get("/api/v1/knowledge/documents/" + a5 + "/tables");
            JsonNode degraded = null;
            for (JsonNode t : tables.get("data")) {
                if (t.get("pageNumber").asInt() == 2) {
                    degraded = t;
                }
            }
            if (degraded == null) {
                diff("T7-a5p2", "IMAGE_DEGRADED_TABLE_ABSENT", "");
            } else {
                compared++;
                if (!"DEGRADED".equals(degraded.get("status").asText())) {
                    diff("T7-a5p2", "STATUS_NOT_DEGRADED", degraded.get("status").asText());
                }
                if (!hasFlag(degraded, "IMAGE_TABLE_NOT_PARSED")) {
                    diff("T7-a5p2", "FLAG_IMAGE_TABLE_NOT_PARSED_MISSING", flagsOf(degraded));
                }
            }
        } else {
            diff("T7-a5p2", "MISSING_DOC", "d41-fx-anomaly-a5.pdf");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedBy", "D41TableReconcile");
        report.put("cellsCompared", compared);
        report.put("diffCount", DIFFS.size());
        report.put("diffs", DIFFS);
        Files.writeString(Path.of(opt.get("out")),
                M.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("RECONCILE cellsCompared=" + compared + " diffs=" + DIFFS.size());
        DIFFS.forEach(d -> System.out.println("  DIFF " + d));
        System.exit(DIFFS.isEmpty() ? 0 : 1);
    }

    static void reconcileTable(String tableId, String docId, List<JsonNode> cells) throws Exception {
        JsonNode tables = get("/api/v1/knowledge/documents/" + docId + "/tables");
        int maxRow = -1;
        int maxCol = 0;
        for (JsonNode c : cells) {
            int row = c.get("row").asInt();
            if (row >= 0) {
                maxRow = Math.max(maxRow, row);
                maxCol = Math.max(maxCol, c.get("col").asInt());
            }
        }
        int cols = maxCol + 1;
        List<Integer> pages = new ArrayList<>();
        for (JsonNode c : cells) {
            int p = c.get("page").asInt();
            if (!pages.contains(p)) {
                pages.add(p);
            }
        }

        for (int page : pages) {
            List<JsonNode> pageCells = cells.stream()
                    .filter(c -> c.get("page").asInt() == page).toList();
            List<String> expectedHeader = new ArrayList<>();
            for (JsonNode c : pageCells) {
                if (c.get("row").asInt() == 0 && c.get("isHeader").asBoolean()) {
                    while (expectedHeader.size() <= c.get("col").asInt()) {
                        expectedHeader.add(null);
                    }
                    expectedHeader.set(c.get("col").asInt(), c.get("text").asText());
                }
            }
            JsonNode table = findTableByPage(tables.get("data"), page);
            if (table == null) {
                diff(tableId, "TABLE_NOT_FOUND_PAGE", String.valueOf(page));
                continue;
            }
            // 表头快照逐格
            for (int c = 0; c < expectedHeader.size(); c++) {
                if (expectedHeader.get(c) == null) {
                    continue;
                }
                compared++;
                String actual = table.get("header").get(c).asText();
                if (!expectedHeader.get(c).equals(actual)) {
                    diff(tableId, "HEADER[" + page + "][" + c + "]", actual);
                }
            }
            JsonNode rows = get("/api/v1/knowledge/tables/" + table.get("id").asLong() + "/rows");
            for (int r = 1; r <= maxRow; r++) {
                JsonNode row = findRow(rows.get("data"), r - 1);
                int finalR = r;
                boolean rowInTruth = pageCells.stream()
                        .anyMatch(c -> c.get("row").asInt() == finalR);
                if (!rowInTruth) {
                    continue;
                }
                if (row == null) {
                    diff(tableId, "ROW_MISSING", page + "/" + r);
                    continue;
                }
                for (int c = 0; c < cols; c++) {
                    JsonNode truthCell = findCell(pageCells, r, c);
                    JsonNode actual = row.get("cells").get(c);
                    compared++;
                    if (truthCell == null) {
                        // 网格内缺项：期望缺失格（null 占位不串列）
                        if (actual.get("value").isNull()) {
                            if (!hasFlag(table, "COLUMN_MISALIGNMENT")) {
                                diff(tableId, "FLAG_COLUMN_MISALIGNMENT_MISSING", flagsOf(table));
                            }
                        } else {
                            diff(tableId, "EXPECTED_MISSING_GOT_VALUE",
                                    page + "/" + r + "/" + c + "=" + actual.get("value").asText());
                        }
                        continue;
                    }
                    String text = truthCell.get("text").asText();
                    if (truthCell.get("isHeader").asBoolean()) {
                        if (!text.equals(actual.get("header").asText())) {
                            diff(tableId, "CELL_HEADER_MISMATCH", page + "/" + r + "/" + c);
                        }
                        continue;
                    }
                    if (actual.get("value").isNull() || !text.equals(actual.get("value").asText())) {
                        diff(tableId, "VALUE", page + "/" + r + "/" + c
                                + " expect=" + text + " actual=" + actual.path("value").asText("null"));
                        continue;
                    }
                    String expectType = truthCell.get("expectType").asText();
                    if ("merged".equals(expectType)) {
                        if (!hasFlag(table, "MERGED_CELL")) {
                            diff(tableId, "FLAG_MERGED_CELL_MISSING", flagsOf(table));
                        }
                    } else {
                        String actualType = actual.get("type").asText();
                        if (!expectType.equals(actualType)) {
                            diff(tableId, "TYPE", page + "/" + r + "/" + c
                                    + " expect=" + expectType + " actual=" + actualType);
                        }
                        String expectUnit = truthCell.hasNonNull("expectUnit")
                                ? truthCell.get("expectUnit").asText() : "";
                        String actualUnit = actual.path("unit").asText("");
                        boolean expectUnitNull = actualUnit.isEmpty()
                                || "null".equals(actual.path("unit").asText(null));
                        if (!expectUnit.isEmpty() && !expectUnit.equals(actual.path("unit").asText())) {
                            diff(tableId, "UNIT", page + "/" + r + "/" + c
                                    + " expect=" + expectUnit + " actual=" + actual.path("unit").asText("null"));
                        } else if (expectUnit.isEmpty() && !expectUnitNull) {
                            diff(tableId, "UNIT_SHOULD_BE_NULL", page + "/" + r + "/" + c
                                    + " actual=" + actualUnit);
                        }
                    }
                }
            }
            // 负项：row=-1 文本禁止出现
            for (JsonNode c : pageCells) {
                if (c.get("row").asInt() != -1) {
                    continue;
                }
                compared++;
                String banned = c.get("text").asText();
                for (JsonNode row : rows.get("data")) {
                    for (JsonNode cell : row.get("cells")) {
                        if (banned.equals(cell.path("value").asText(null))) {
                            diff(tableId, "NEGATIVE_TEXT_PRESENT", banned + " @" + page);
                        }
                    }
                }
            }
        }
        // a3 续表关联断言
        if (tableId.startsWith("T4-") && pages.size() == 2) {
            JsonNode main = findTableByPage(tables.get("data"), pages.get(0));
            JsonNode cont = findTableByPage(tables.get("data"), pages.get(1));
            compared++;
            if (main == null || cont == null) {
                diff(tableId, "CONTINUATION_TABLES_INCOMPLETE", "");
            } else {
                if (cont.get("continuationOfTableId").isNull()
                        || cont.get("continuationOfTableId").asLong() != main.get("id").asLong()) {
                    diff(tableId, "CONTINUATION_LINK", String.valueOf(cont.path("continuationOfTableId")));
                }
                if (!hasFlag(cont, "CONTINUATION_HEADER_SKIPPED")) {
                    diff(tableId, "FLAG_CONTINUATION_HEADER_SKIPPED_MISSING", flagsOf(cont));
                }
            }
        }
    }

    static JsonNode findTableByPage(JsonNode tables, int page) {
        for (JsonNode t : tables) {
            if (t.get("pageNumber").asInt() == page) {
                return t;
            }
        }
        return null;
    }

    static JsonNode findRow(JsonNode rows, int rowIndex) {
        for (JsonNode r : rows) {
            if (r.get("rowIndex").asInt() == rowIndex) {
                return r;
            }
        }
        return null;
    }

    static JsonNode findCell(List<JsonNode> pageCells, int row, int col) {
        for (JsonNode c : pageCells) {
            if (c.get("row").asInt() == row && c.get("col").asInt() == col) {
                return c;
            }
        }
        return null;
    }

    static boolean hasFlag(JsonNode table, String flag) {
        for (JsonNode f : table.get("detectFlags")) {
            if (flag.equals(f.asText())) {
                return true;
            }
        }
        return false;
    }

    static String flagsOf(JsonNode table) {
        return table.get("detectFlags").toString();
    }

    static JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = M.readTree(response.body());
        if (body.path("code").asInt() != 200) {
            throw new IOException("API " + path + " -> " + response.body());
        }
        return body;
    }

    static void diff(String tableId, String kind, String detail) {
        DIFFS.add(tableId + " " + kind + " " + detail);
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            map.put(args[i].replace("--", ""), args[i + 1]);
        }
        return map;
    }
}
