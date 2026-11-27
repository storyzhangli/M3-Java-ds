package com.zl.d44.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.rerank.LocalLexicalReranker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S10 故障演练轮（任务书 §6.4：删除闭环注入失败 → FAILED 落账 → 恢复 → retry 收敛）。
 *
 * <p>独立装配上下文（区别于 {@link E2eRoundIT} 的常规 e2e 上下文）：</p>
 * <ul>
 *   <li>{@code app.vector-index.fault-injection.enabled=true}：@Primary 装饰器接管
 *       vectorStore，delete(List) 计数从本上下文装配起算（每类独立 counter，
 *       与 round 轮上下文互不串扰）；</li>
 *   <li>{@code fail-delete-call=1}：删除闭环首批 delete 必炸（新文档索引仅 add，
 *       闭环前无 delete 消耗计数——round19 语义复核）；</li>
 *   <li>演练对象为独立夹具 {@code d44e2e-drill-001}，不触碰 perm/matrix 冻结夹具，
 *       与 E2eRoundIT 执行顺序无耦合。</li>
 * </ul>
 *
 * <p>触发：-DD44_E2E_DRILL=true（pom 桥接属性 d44.e2e.drill，缺省 false 零污染）。
 * 观测落盘 docs/evidence/D44/e2e/fault-drill-observation.json，由 P8 报告合并
 * S10 终局判定（round 轮 S10=BLOCKED 的装配事实保留原样，如实双记录）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@TestPropertySource(properties = {
        "app.vector-index.fault-injection.enabled=true",
        "app.vector-index.fault-injection.fail-delete-call=1",
        "app.vector-index.fault-injection.fail-add-call=0"
})
@EnabledIfSystemProperty(named = "D44_E2E_DRILL", matches = "true")
class E2eFaultDrillIT {

    private static final String TOKEN_ADMIN = "fixture-e2e-token-admin-a";
    private static final String DRILL_DOC = "d44e2e-drill-001";
    private static final String DRILL_SENTENCE =
            "D44-S10 演练夹具：冷却回路压力校验句，仅用于删除闭环故障注入演练。";

    /** BC-11 同型装配补丁：e2e profile pgvector=true+rerank=false 需本地重排器 Bean。 */
    @TestConfiguration
    static class DrillAssemblyPatch {
        @Bean
        LocalLexicalReranker localLexicalRerankerForDrill() {
            return new LocalLexicalReranker();
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.zl.d44.knowledge.vectorindex.service.DocumentVectorIndexService indexService;
    @Autowired
    private VectorStore vectorStore;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void s10DeleteFailureDrill() throws Exception {
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("scenarioId", "S10");
        obs.put("fixtureDocumentId", DRILL_DOC);
        obs.put("injection", "fault-injection enabled, fail-delete-call=1（本上下文 counter 独立起算）");

        preCleanDrillDoc();
        // QC-R02 同型整改：drill 走真实 HTTP admin 通道，principal 播种自包含
        // （同一事实源 fixture-truth.json，仅本类实际用到的 admin 行）
        seedAdminPrincipal();
        seedDrillDoc();
        int indexedChunks = chunkIdsOfDrill().size();
        obs.put("seededChunks", indexedChunks);
        obs.put("pgRowsAfterIndex", pgRowsOfDrill());

        // ① 注入轮：HTTP 删除 → 闭环首批 vectorStore.delete=call#1 → INJECTED throw
        ResponseEntity<String> del = rest.exchange(
                "/api/v1/knowledge/documents/" + DRILL_DOC, HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()), String.class);
        obs.put("httpDeleteStatus", del.getStatusCode().value());
        obs.put("httpDeleteBody", trim(del.getBody()));
        String failedRow = querySyncRow("FAILED");
        obs.put("syncRowAfterInjectedDelete", failedRow);
        boolean failedLedgered = "FAILED".equals(failedRow);
        obs.put("assertFailedLedgered", failedLedgered);

        // ② 恢复轮：retry 端点重放幂等闭环 → delete call#2 透传成功 → 行收敛
        ResponseEntity<String> retry = rest.exchange("/api/v1/knowledge/sync/retry",
                HttpMethod.POST, new HttpEntity<>(
                        "{\"documentId\":\"" + DRILL_DOC + "\",\"operation\":\"DELETE\","
                                + "\"version\":\"\"}", jsonAdminHeaders()), String.class);
        obs.put("httpRetryStatus", retry.getStatusCode().value());
        obs.put("httpRetryBody", trim(retry.getBody()));
        String rowAfterRetry = querySyncRow(null);
        obs.put("syncRowAfterRetry", rowAfterRetry);
        // BC-18 台账口径：成功收敛允许“行被置 SYNCED”或“行不存在”（成功不落账族）；
        // 唯一硬约束=不再存在未收敛（PENDING/FAILED）行。
        boolean converged = rowAfterRetry == null || "SYNCED".equals(rowAfterRetry);
        obs.put("assertRetryConverged", converged);

        // ③ 终局双端确认：向量行清零（store 层直查，不经权限过滤）+ 生命周期 DELETED
        int pgRowsAfterConverge = pgRowsOfDrill();
        String lifecycle = jdbc.queryForObject(
                "SELECT lifecycle_status FROM knowledge_document WHERE document_id = ?",
                String.class, DRILL_DOC);
        obs.put("pgRowsAfterConverge", pgRowsAfterConverge);
        obs.put("lifecycleAfterConverge", lifecycle);
        boolean vectorsGone = pgRowsAfterConverge == 0 && "DELETED".equals(lifecycle);
        obs.put("assertVectorsGoneAndDeleted", vectorsGone);

        boolean verdict = failedLedgered && converged && vectorsGone;
        obs.put("verdict", verdict ? "PASS" : "FAIL");

        Path out = Path.of(repoRoot().toString(),
                "docs", "evidence", "D44", "e2e", "fault-drill-observation.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, om.writerWithDefaultPrettyPrinter().writeValueAsString(obs),
                StandardCharsets.UTF_8);
        System.out.println("[E2E-Drill] verdict=" + obs.get("verdict")
                + " failedLedgered=" + failedLedgered + " converged=" + converged
                + " vectorsGone=" + vectorsGone
                + " retryBody=" + trim(retry.getBody()));
        if (!verdict) {
            org.junit.jupiter.api.Assertions.fail("S10 演练未收敛，观测=" + obs);
        }
    }

    // ---- 播种（与 E2eRoundIT BC-12/13 同口径：JDBC 直插等价行 + 真实 import HTTP + 页行补种） ----

    /** QC-R02 同型整改：principal 播种自包含（事实源=fixture-truth.json，仅 admin 行）。 */
    private void seedAdminPrincipal() throws Exception {
        Path truth = repoRoot().resolve("docs").resolve("evidence").resolve("D44")
                .resolve("probe").resolve("fixture-truth.json");
        JsonNode admin = om.readTree(Files.readString(truth, StandardCharsets.UTF_8))
                .path("permissionFixtures").path("principals").path(0);
        if (!"ADMIN".equals(admin.path("role").asText())) {
            throw new IllegalStateException("fixture-truth principals[0] 非 admin，播种中止");
        }
        jdbc.update("REPLACE INTO d44_auth_principal (token_sha256, user_id, tenant_id,"
                        + " dept_id, device_scopes, role, enabled, expires_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                admin.path("sha256").asText(), admin.path("user").asText(), admin.path("tenant").asText(),
                admin.path("dept").asText(), om.writeValueAsString(admin.path("scopes")),
                admin.path("role").asText(), admin.path("enabled").asInt(),
                admin.path("expires").isNull() ? null : admin.path("expires").asText());
    }
    private void seedDrillDoc() {
        jdbc.update("REPLACE INTO knowledge_document (document_id, source_system, source, title,"
                        + " version, status, lifecycle_status, content_length, error_code, error_message,"
                        + " created_at, updated_at, tenant_id, dept_id, device_id)"
                        + " VALUES (?, 'd44-e2e', 'manual', ?, 1, 'PENDING', 'ACTIVE', 0, '', '',"
                        + " NOW(), NOW(), 'T-A', 'D-A1', 'dev-a1')",
                DRILL_DOC, "E2E S10 演练夹具");
        ResponseEntity<String> imp = rest.exchange(
                "/api/v1/knowledge/documents/" + DRILL_DOC + "/import",
                HttpMethod.POST,
                new HttpEntity<>(multipart("d44e2e-drill-001.txt", DRILL_SENTENCE + "\n"),
                        adminHeaders()), String.class);
        if (imp.getStatusCode().value() != 200) {
            org.junit.jupiter.api.Assertions.fail("演练夹具 import 失败: " + imp.getBody());
        }
        String text = DRILL_SENTENCE.strip();
        jdbc.update("INSERT INTO knowledge_document_page (document_id, source, title, version,"
                        + " page_number, content, content_length, blank, created_at, updated_at,"
                        + " character_count, blank_page, parser_name, parser_version)"
                        + " VALUES (?, 'manual', ?, 1, 1, ?, ?, 0, NOW(), NOW(), ?, 0, 'UTF-8 text',"
                        + " 'e2e-fixture')",
                DRILL_DOC, "E2E S10 演练夹具", text, text.length(), text.length());
        indexService.indexFromPages(DRILL_DOC, null);
    }

    private void preCleanDrillDoc() {
        // 本上下文 PG 清理不走 vectorStore.delete（避免消耗注入计数），直查后按 id 走
        // delegate 语义：FaultInjectingVectorStore.delete 仅第 N 次拦截，N=1——因此
        // 清理放在注入断言之后无法执行，改为“残留幂等容忍”：REPLACE INTO + indexFromPages
        // 同 payload 幂等覆盖；旧行由删除闭环 delete(idList) 一并清除（含历史残留 id）。
        jdbc.update("DELETE FROM vector_chunk_index WHERE document_id = ?", DRILL_DOC);
        jdbc.update("DELETE FROM d44_document_sync WHERE document_id = ?", DRILL_DOC);
        jdbc.update("DELETE FROM vector_index_task WHERE document_id = ?", DRILL_DOC);
        jdbc.update("DELETE FROM knowledge_document_page WHERE document_id = ?", DRILL_DOC);
    }

    private List<String> chunkIdsOfDrill() {
        return jdbc.queryForList(
                "SELECT chunk_id FROM vector_chunk_index WHERE document_id = ?", String.class, DRILL_DOC);
    }

    /** store 层直查向量行数（装饰器 similaritySearch 透传，不触发权限过滤与注入）。 */
    private int pgRowsOfDrill() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query("压力")
                .topK(50)
                .filterExpression(b.eq("documentId", DRILL_DOC).build())
                .build();
        List<Document> docs = vectorStore.similaritySearch(request);
        return docs == null ? 0 : docs.size();
    }

    /** 台账行状态（operation=DELETE 域内）：statusFilter=null 取最新行状态；无行返回 null。 */
    private String querySyncRow(String statusFilter) {
        List<String> statuses = statusFilter == null
                ? jdbc.queryForList("SELECT sync_status FROM d44_document_sync WHERE document_id = ?"
                        + " AND operation = 'DELETE' ORDER BY updated_at DESC LIMIT 1", String.class, DRILL_DOC)
                : jdbc.queryForList("SELECT sync_status FROM d44_document_sync WHERE document_id = ?"
                        + " AND operation = 'DELETE' AND sync_status = ? LIMIT 1", String.class, DRILL_DOC,
                        statusFilter);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    // ---- 通用工具（与 E2eRoundIT 同型，独立复制避免跨类耦合） ----

    private static Path repoRoot() {
        for (String candidate : new String[]{".", "..", "../.."}) {
            java.io.File docs = new java.io.File(candidate, "docs");
            if (new java.io.File(docs, "evidence").isDirectory()) {
                return Path.of(candidate);
            }
        }
        throw new IllegalStateException("未找到仓库根 docs/evidence");
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 400 ? body : body.substring(0, 400) + "...";
    }

    private org.springframework.util.MultiValueMap<String, Object> multipart(
            String fileName, String content) {
        Resource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        org.springframework.util.LinkedMultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", file);
        return body;
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + TOKEN_ADMIN);
        return h;
    }

    private HttpHeaders jsonAdminHeaders() {
        HttpHeaders h = adminHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
