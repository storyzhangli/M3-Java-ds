package com.zl.d44.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.access.AuthContext;
import com.zl.d44.access.AuthContextHolder;
import com.zl.d44.knowledge.KnowledgeCorpus;
import com.zl.d44.knowledge.document.KnowledgeDocumentService;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d44.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d44.knowledge.sync.DocumentSyncRepository;
import com.zl.d44.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d44.rag.service.CitationExistenceValidator;
import com.zl.d44.rag.service.RagOrchestrator;
import com.zl.d44.rerank.LocalLexicalReranker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.VectorStore;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5 mock 轮：S01–S12 + P01–P10 + 固定评估 真实执行（e2e profile，access=true，真实 MySQL/PG）。
 *
 * <p>触发：-DD44_E2E_RUN=true（缺省不装配不执行，零污染默认回归——G-1 纪律）。
 * 远程调用零容忍（H4/C15）：AI_MODE 缺省 mock、two/hybrid/rerank=false（application-e2e.yml），
 * MockAiChatService 结构熔断零字段。泄漏硬断言（G-3/P0 熔断）；场景 FAIL 不中断——
 * 如实落盘进 bad-case 分类（§6.4），由 P6 收敛。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@EnabledIfSystemProperty(named = "D44_E2E_RUN", matches = "true")
class E2eRoundIT {

    private static final String TOKEN_ADMIN = "fixture-e2e-token-admin-a";
    private static final AuthContext ADMIN_CTX =
            new AuthContext("e2e-admin-a", "T-A", "D-A1", Set.of("dev-a1"), AuthContext.ROLE_ADMIN);
    private static final Pattern AUTH_CODE = Pattern.compile("AUTH_[A-Z_]+");
    /** surefire 工作目录=模块级，docs 在仓库根：两级探测（D42 experiment Runner 同型先例）。 */
    private static Path repoRoot() {
        for (String candidate : new String[]{".", "..", "../.."}) {
            java.io.File docs = new java.io.File(candidate, "docs");
            if (new java.io.File(docs, "evidence").isDirectory()) {
                return Path.of(candidate);
            }
        }
        throw new IllegalStateException("未找到仓库根（docs/evidence 目录）——cwd=" + new java.io.File(".").getAbsolutePath());
    }

    @Autowired TestRestTemplate rest;
    @Autowired KnowledgeDocumentService docService;
    @Autowired DocumentVectorIndexService indexService;
    @Autowired SimilarityRetrievalService retrieval;
    @Autowired RagOrchestrator ragOrchestrator;
    @Autowired DocumentSyncRepository syncRepository;
    @Autowired CitationExistenceValidator citationValidator;
    @Autowired KnowledgeCorpus corpus;
    @Autowired JdbcTemplate jdbc;
    @Autowired VectorStore vectorStore;
    @Autowired ObjectMapper om;

    /** S09 删除前捕获 lc-002 的 chunkId 清单（S12 引用探针对象）。 */
    private List<String> lc2ChunkIds = new ArrayList<>();

    @BeforeEach
    void bindAdminContext() {
        AuthContextHolder.restore(ADMIN_CTX);
    }

    @AfterEach
    void unbindContext() {
        AuthContextHolder.clear();
    }

    /**
     * e2e 组合装配补丁（D44 bad case BC-11，登记 fix-records）：
     * RerankValueExperimentRunner 门槛键为 app.vectorstore.pgvector.enabled（D42 遗留），
     * 而 e2e 需要 pgvector=true + rerank=false（主链零漂移 G0 口径）。该组合下实验 Runner
     * 被装配但 LocalLexicalReranker（生产装配点仅在 RerankConfiguration，门槛 rerank.enabled）
     * 缺 Bean → context 启动失败。此处仅补 Bean 满足构造注入：实验 Runner 在 e2e 轮不被调用、
     * 主链 rerank 仍关闭——零行为影响；main 门槛键归属问题入 fix-records 供后续迭代评估。
     */
    @TestConfiguration
    static class E2eAssemblyPatch {

        @Bean
        LocalLexicalReranker localLexicalRerankerForE2eAssemblyOnly() {
            return new LocalLexicalReranker();
        }
    }

    @Test
    @DisplayName("D44 mock 轮全量执行：S01–S12+P01–P10+固定评估 三件套落盘")
    void fullRound() throws Exception {
        E2eReportWriter writer = new E2eReportWriter(repoRoot().resolve("docs").resolve("evidence").resolve("D44"));

        preCleanFixtures();
        // QC-R02：principal 播种收回 IT（P7 终局清理后轮次仍可独立复跑）
        seedPrincipals();
        // lc-002 引用面夹具（§6.3 S09/S12 对象）：S01 只建 lc-001，此处显式播种（fixture-truth 冻结句），
        // 捕获 chunkIds 供 S12 引用探针——捕获时序必须晚于索引（缺陷自纠：原实现先读恒空）。
        gateway().registerAndImport(E2eScenarioRunner.DOC_LC2, "E2E 生命周期夹具二",
                "d44e2e-lc-002.txt", "润滑泵启动延时 15 秒\n设备通用维护说明段落，用于引用与删除闭环断言。"
                        + "出现过热现象时应先隔离设备再检查润滑回路。\n");
        indexService.indexFromPages(E2eScenarioRunner.DOC_LC2, null);
        lc2ChunkIds = chunkIdsOf(E2eScenarioRunner.DOC_LC2);

        // ---- S01–S12 ----
        E2eScenarioRunner runner = new E2eScenarioRunner(gateway(), indexService, retrieval,
                ragOrchestrator, syncRepository, jdbc);
        // BC-19b：S06 生成闭环走 HTTP + user-a 身份（bean 直调无 AuthContext 检索 fail-closed）
        runner.setAnswerProbe((query, token) -> {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("Authorization", "Bearer " + token);
            String body;
            try {
                body = om.writeValueAsString(Map.of("query", query, "topK", 5));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            return rest.exchange("/api/v1/rag/answer",
                    HttpMethod.POST, new HttpEntity<>(body, h), String.class).getBody();
        });
        E2eScenarioRunner.CitationProbe citationProbe = (docId) -> {
            boolean missing = citationValidator.validate(lc2ChunkIds).hasMissing();
            return missing ? "CITATION_TARGET_DELETED" : "OK";
        };
        List<ScenarioRecord> scenarios = runner.runAll(null, citationProbe);
        writer.writeJson("e2e/e2e-scenario-records.json", Map.of(
                "round", "mock-full-1", "profile", "e2e", "aiMode", "mock",
                "records", scenarios));

        // ---- P01–P10 ----
        seedPermFixturesIfNeeded();
        List<PermissionCaseRecord> matrix = new PermissionMatrixRunner(
                httpProbe(), auditProbe(), internalProbe(), E2eScenarioRunner.DOC_LC2).runAll();
        writer.writeJson("e2e/permission-matrix-results.json", Map.of(
                "round", "mock-full-1",
                "expectedFrozen", "fixture-truth.permissionFixtures.expectedFrozen verbatim",
                "records", matrix));

        long leakage = matrix.stream().filter(PermissionCaseRecord::leakage).count();
        assertEquals(0, leakage, "G-3/P0：越权泄漏出现即熔断（详见 permission-matrix-results.json）");

        // ---- 固定评估 ----
        E2eFixedEvalRunner.EvalResult eval =
                new E2eFixedEvalRunner(retrieval, corpus, om).run(repoRoot().resolve("docs").resolve("evidence").resolve("D25").resolve("rag-design").resolve("questions.jsonl"));
        writer.writeJson("e2e/fixed-eval-results.json", Map.of(
                "round", "mock-full-1", "engine", eval.engine(),
                "metrics", eval.metrics(), "saturationNote", eval.saturationNote(),
                "frozenFingerprintsRef", "docs/evidence/D44/probe/storage-inventory.json",
                "outcomes", eval.outcomes()));

        // 汇总打印（判定以落盘件为准；此轮不硬断言场景 FAIL——P6 bad-case 收敛）
        System.out.println("[E2E-Round] scenarios=" + scenarios.stream()
                .map(r -> r.scenarioId() + ":" + r.verdict()).toList());
        System.out.println("[E2E-Round] matrix=" + matrix.stream()
                .map(r -> r.caseId() + ":" + r.verdict()).toList());
        System.out.println("[E2E-Round] metrics=" + eval.metrics());
    }

    // ---- principal 播种（QC-R02 P1 整改：IT 自包含，单一事实源=fixture-truth.json）----

    /**
     * QC-R02 整改：P5 期间 principal 六行经 temp_docs/D44/ddl/principal-seed.sql 一次性手工
     * 执行，P7 清理删除后即 IT 复跑断裂（broken 轮实证：mvn-qc-rerun-round-broken.txt）。
     * 播种收回 IT：token_sha256/user/tenant/dept/scopes/role/enabled/expires 全部读自
     * docs/evidence/D44/probe/fixture-truth.json permissionFixtures.principals[]（冻结值
     * 单一事实源，明文 token 不落 DB，H9）；REPLACE INTO 主键幂等，重复执行零漂移。
     */
    private void seedPrincipals() throws Exception {
        Path truth = repoRoot().resolve("docs").resolve("evidence").resolve("D44")
                .resolve("probe").resolve("fixture-truth.json");
        JsonNode principals = om.readTree(Files.readString(truth, StandardCharsets.UTF_8))
                .path("permissionFixtures").path("principals");
        if (!principals.isArray() || principals.isEmpty()) {
            throw new IllegalStateException("fixture-truth.json principals 缺失，播种中止");
        }
        for (JsonNode p : principals) {
            jdbc.update("REPLACE INTO d44_auth_principal (token_sha256, user_id, tenant_id,"
                            + " dept_id, device_scopes, role, enabled, expires_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    p.path("sha256").asText(), p.path("user").asText(), p.path("tenant").asText(),
                    p.path("dept").asText(), om.writeValueAsString(p.path("scopes")),
                    p.path("role").asText(), p.path("enabled").asInt(),
                    p.path("expires").isNull() ? null : p.path("expires").asText());
        }
    }

    // ---- 前置清理（幂等起跑：仅 d44e2e- 前缀，不触碰他行）----

    private void preCleanFixtures() {
        List<String> ids = jdbc.queryForList(
                "SELECT document_id FROM knowledge_document WHERE document_id LIKE 'd44e2e-%'", String.class);
        for (String id : ids) {
            for (String chunkId : chunkIdsOf(id)) {
                try {
                    vectorStore.delete(List.of(chunkId));
                } catch (RuntimeException ignored) {
                    // PG 行可能已不存在（台账与 PG 漂移），P7 对账兜底
                }
            }
        }
        jdbc.update("DELETE FROM vector_chunk_index WHERE document_id LIKE 'd44e2e-%'");
        // BC-17（fix-records）：preClean 首版漏 vector_index_task——上轮 SWITCH 后的活动版本指针残留
        // 使新一轮 indexFromPages(V1) 被“迟到写入”守卫拒绝（S03 FAIL 根因）。任务态必须与数据态同清。
        jdbc.update("DELETE FROM vector_index_task WHERE document_id LIKE 'd44e2e-%'");
        jdbc.update("DELETE FROM knowledge_document_page WHERE document_id LIKE 'd44e2e-%'");
        jdbc.update("DELETE FROM knowledge_document_content WHERE document_id LIKE 'd44e2e-%'");
        jdbc.update("DELETE FROM d44_document_sync WHERE document_id LIKE 'd44e2e-%'");
        jdbc.update("DELETE FROM d44_admin_audit WHERE target_document_id LIKE 'd44e2e-%'");
        jdbc.update("DELETE FROM knowledge_document WHERE document_id LIKE 'd44e2e-%'");
    }

    private List<String> chunkIdsOf(String documentId) {
        return jdbc.queryForList(
                "SELECT chunk_id FROM vector_chunk_index WHERE document_id = ?", String.class, documentId);
    }

    /** 实库版本指针（发布 4091 分支后指针已 +1，页行补种需按新指针版本）。 */
    private int currentVersionOf(String documentId) {
        Integer v = jdbc.queryForObject(
                "SELECT version FROM knowledge_document WHERE document_id = ?", Integer.class, documentId);
        return v == null ? 1 : v;
    }

    /**
     * BC-13（登记 fix-records）：主码 KnowledgeDocumentParser.parse 对 txt/html/docx 路径
     * pages 恒为 List.of()（页级文本仅 PDF parsePdf 产出，L108/L141 实码），而
     * DocumentVectorIndexService.indexFromPages 依赖 chunking.preview 的页面级文本
     * （KnowledgeDocumentChunkingService.source L53-58）。D44 mock 轮夹具因 PDF 缺失退化为
     * txt（KP2 已授权），import/发布 HTTP 链与索引编排均为真实主码，仅在此按 PDF 解析器
     * 同等产物补种单页行，使“导入→索引→发布→重建索引”编排面可端到端执行。
     */
    private void supplementPageRow(String documentId, int version, String text) {
        jdbc.update("DELETE FROM knowledge_document_page WHERE document_id = ? AND version = ?",
                documentId, version);
        String title = jdbc.queryForObject(
                "SELECT title FROM knowledge_document WHERE document_id = ?", String.class, documentId);
        jdbc.update("INSERT INTO knowledge_document_page (document_id, source, title, version, page_number,"
                        + " content, content_length, blank, created_at, updated_at, character_count,"
                        + " blank_page, parser_name, parser_version)"
                        + " VALUES (?, 'manual', ?, ?, 1, ?, ?, 0, NOW(), NOW(), ?, 0, 'UTF-8 text', 'e2e-fixture')",
                documentId, title, version, text, text.length(), text.length());
    }

    // ---- 生命周期网关（HTTP 面真实端点，ADMIN token）----

    private E2eScenarioRunner.LifecycleGateway gateway() {
        return new E2eScenarioRunner.LifecycleGateway() {
            @Override
            public KnowledgeDocumentView registerAndImport(String documentId, String title,
                                                           String fileName, String content) {
                return registerAndImportTenant(documentId, title, null, fileName, content);
            }

            @Override
            public KnowledgeDocumentView registerWithTenant(String documentId, String title, String tenantId,
                                                             String fileName, String content) {
                return registerAndImportTenant(documentId, title, tenantId, fileName, content);
            }

            @Override
            public KnowledgeDocumentView publishVersion(String documentId, String fileName, String content) {
                ResponseEntity<String> resp = rest.exchange("/api/v1/knowledge/documents/" + documentId + "/versions",
                        HttpMethod.POST, new HttpEntity<>(multipart(fileName, content), adminHeaders()), String.class);
                JsonNode body;
                try {
                    body = om.readTree(resp.getBody());
                } catch (Exception ex) {
                    throw new IllegalStateException("发布响应非 JSON: " + resp.getBody(), ex);
                }
                if (body.path("code").asInt(-1) == 4091) {
                    // BC-13 组合处置：txt 夹具 parsed.pages() 恒空 → Controller 编排的“发布后重建索引”
                    // 步骤在 chunking.source 处 4091；发布写面（republishClaim/complete/版本指针）
                    // 此时已提交。IT 按 PDF 口径补种页行后，以 bean 直调复现该索引编排步骤
                    //（indexFromPages 真实主码：新向量→SWITCH→旧版清理），不绕过任何被测面。
                    supplementPageRow(documentId, currentVersionOf(documentId), content.strip());
                    indexService.indexFromPages(documentId, null);
                    return viewFrom(rest.exchange("/api/v1/knowledge/documents/" + documentId,
                            HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class));
                }
                KnowledgeDocumentView view = viewFrom(resp);
                supplementPageRow(documentId, Integer.parseInt(view.version()), content.strip());
                return view;
            }

            @Override
            public KnowledgeDocumentView deleteDocument(String documentId) {
                ResponseEntity<String> resp = rest.exchange("/api/v1/knowledge/documents/" + documentId,
                        HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), String.class);
                return viewFrom(resp);
            }

            private KnowledgeDocumentView registerAndImportTenant(String documentId, String title, String tenantId,
                                                                  String fileName, String content) {
                // BC-12（登记 fix-records）：D34 真实登记端点 DTO（KnowledgeDocumentRegistrationRequest）
                // 无 documentId 字段——服务端恒发 UUID，任务书 §6.3 的 d44e2e- 前缀夹具 ID 无法经该端点指定。
                // 处置：登记落库面以 JDBC 直插等价行复现（列集对齐 D34 register 落库实况），
                // import/发布/删除等 D44 被测写面全部保留真实 HTTP。真实 register HTTP 面
                // 已由 D34/D40 回归覆盖，此处不再重复。
                String[] meta = fixtureMetadata(documentId, tenantId);
                jdbc.update("REPLACE INTO knowledge_document (document_id, source_system, source, title,"
                                + " version, status, lifecycle_status, content_length, error_code, error_message,"
                                + " created_at, updated_at, tenant_id, dept_id, device_id)"
                                + " VALUES (?, 'd44-e2e', 'manual', ?, 1, 'PENDING', 'ACTIVE', 0, '', '',"
                                + " NOW(), NOW(), ?, ?, ?)",
                        documentId, title, meta[0], meta[1], meta[2]);
                ResponseEntity<String> imp = rest.exchange("/api/v1/knowledge/documents/" + documentId + "/import",
                        HttpMethod.POST, new HttpEntity<>(multipart(fileName, content), adminHeaders()), String.class);
                KnowledgeDocumentView view = viewFrom(imp);
                // BC-13 页行补种钩子（同上）：import 走真实 HTTP 状态机，页级文本按 PDF 口径补齐。
                supplementPageRow(documentId, Integer.parseInt(view.version()), content.strip());
                return view;
            }

            /** 夹具元数据映射（fixture-truth 冻结）：lc-001/lc-002/perm-a→T-A/D-A1/dev-a1；perm-b→T-B/D-B1/dev-b1。 */
            private String[] fixtureMetadata(String documentId, String tenantOverride) {
                return switch (documentId) {
                    case "d44e2e-perm-b" -> new String[]{tenantOverride != null ? tenantOverride : "T-B", "D-B1", "dev-b1"};
                    default -> new String[]{tenantOverride != null ? tenantOverride : "T-A", "D-A1", "dev-a1"};
                };
            }
        };
    }

    private KnowledgeDocumentView viewFrom(ResponseEntity<String> resp) {
        JsonNode data = dataNode(resp.getBody());
        return new KnowledgeDocumentView(
                text(data, "documentId"), text(data, "source"), text(data, "title"),
                text(data, "version"), text(data, "status"), null,
                text(data, "fileName"), text(data, "contentType"), null,
                text(data, "contentHash"), null, null,
                text(data, "errorCode"), text(data, "errorMessage"), false,
                text(data, "tenantId"), text(data, "deptId"), text(data, "deviceId"));
    }

    private JsonNode dataNode(String body) {
        try {
            JsonNode root = om.readTree(body);
            JsonNode code = root.path("code");
            if (code.asInt(-1) != 200 && !root.hasNonNull("data")) {
                fail("HTTP 业务失败: " + body);
            }
            return root.path("data");
        } catch (java.io.IOException ex) {
            throw new AssertionError("响应解析失败: " + body, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private MultiValueMap<String, Object> multipart(String fileName, String content) {
        Resource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
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

    // ---- 权限探针（HTTP 面）----

    private PermissionMatrixRunner.HttpProbe httpProbe() {
        return new PermissionMatrixRunner.HttpProbe() {
            @Override
            public PermissionMatrixRunner.ProbeResponse get(String path, String token) {
                HttpHeaders h = new HttpHeaders();
                if (token != null) {
                    h.set("Authorization", "Bearer " + token);
                }
                ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
                return probe(r.getStatusCode().value(), r.getBody());
            }

            @Override
            public PermissionMatrixRunner.ProbeResponse postSearch(String query, String token) {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                if (token != null) {
                    h.set("Authorization", "Bearer " + token);
                }
                String body;
                try {
                    body = om.writeValueAsString(Map.of("query", query, "topK", 5));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                ResponseEntity<String> r = rest.exchange("/api/v1/rag/answer",
                        HttpMethod.POST, new HttpEntity<>(body, h), String.class);
                return probe(r.getStatusCode().value(), r.getBody());
            }

            @Override
            public PermissionMatrixRunner.ProbeResponse deleteDocument(String documentId, String token) {
                HttpHeaders h = new HttpHeaders();
                h.set("Authorization", "Bearer " + token);
                ResponseEntity<String> r = rest.exchange("/api/v1/knowledge/documents/" + documentId,
                        HttpMethod.DELETE, new HttpEntity<>(h), String.class);
                return probe(r.getStatusCode().value(), r.getBody());
            }

            private PermissionMatrixRunner.ProbeResponse probe(int status, String body) {
                String raw = body == null ? "" : body;
                Matcher matcher = AUTH_CODE.matcher(raw);
                String bizCode = matcher.find() ? matcher.group() : null;
                // BC-16（fix-records）：AccessGuard 拒绝面（requireAdmin/requireDocumentVisible）
                // 抛 RetrievalException(AUTH_FORBIDDEN) 但 advice 渲染的 HTTP msg 为纯中文
                // （"无权访问该文档"/"该操作需要管理员"），机器码 AUTH_FORBIDDEN 仅在异常对象内。
                // HTTP 面判定按实码口径：code=4001 且无 AUTH_* 码 → 归一为 AUTH_FORBIDDEN。
                // msg 无码化属主码可观测性缺口，入 fix-records 供 D44 评估（响应体补 errorCode 字段）。
                if (bizCode == null && raw.contains("\"code\":4001")) {
                    bizCode = "AUTH_FORBIDDEN";
                }
                // BC-15（fix-records）：RagResponse 回显 query 原文，泄漏判定若消费整体会把
                // 请求文本误判为语料泄漏。digest 剔除 "query" 字段回显面后再供泄漏断言。
                String digest = raw.replaceAll("\"query\":\"(?:[^\"\\\\]|\\\\.)*\"", "\"query\":\"\"");
                if (digest.length() > 4000) {
                    digest = digest.substring(0, 4000);
                }
                return new PermissionMatrixRunner.ProbeResponse(status, bizCode, digest);
            }
        };
    }

    private PermissionMatrixRunner.AuditProbe auditProbe() {
        return new PermissionMatrixRunner.AuditProbe() {
            @Override
            public boolean hasAuditRow(String action, String targetDocumentId) {
                Integer n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM d44_admin_audit WHERE action=? AND target_document_id=? AND result='ALLOWED'",
                        Integer.class, action, targetDocumentId);
                return n != null && n > 0;
            }

            @Override
            public boolean hasDeniedAuditRow(String action, String targetDocumentId) {
                Integer n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM d44_admin_audit WHERE action=? AND target_document_id=? AND result='FAILED'",
                        Integer.class, action, targetDocumentId);
                return n != null && n > 0;
            }
        };
    }

    private PermissionMatrixRunner.InternalProbe internalProbe() {
        return () -> {
            AuthContextHolder.clear();
            try {
                retrieval.search(new com.zl.d44.knowledge.retrieval.dto.RetrievalQueryRequest(
                        "E2E P10 内部直调", 5, null, null, null));
                return "NO_EXCEPTION_LEAK";
            } catch (com.zl.d44.knowledge.retrieval.RetrievalException ex) {
                return String.valueOf(ex.getErrorCode());
            } catch (RuntimeException ex) {
                return ex.getClass().getSimpleName();
            } finally {
                AuthContextHolder.restore(ADMIN_CTX);
            }
        };
    }

    // ---- 权限夹具（perm-a/perm-b 幂等补种，租户标签经登记请求透传）----

    private void seedPermFixturesIfNeeded() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE document_id IN (?,?)", Integer.class,
                E2eScenarioRunner.DOC_PERM_A, E2eScenarioRunner.DOC_PERM_B);
        if (n != null && n >= 2) {
            return;
        }
        var a = gateway().registerWithTenant(E2eScenarioRunner.DOC_PERM_A, "E2E 权限夹具 A",
                "T-A", "d44e2e-perm-a.txt", "E2E-PERM-A 租户Alpha设备手册条款：Alpha 专属维护参数 A1。\n");
        indexService.indexFromPages(E2eScenarioRunner.DOC_PERM_A, null);
        var b = gateway().registerWithTenant(E2eScenarioRunner.DOC_PERM_B, "E2E 权限夹具 B",
                "T-B", "d44e2e-perm-b.txt", "E2E-PERM-B 租户Beta设备手册条款（P05/P06/P07 隔离断言对象）：Beta 专属维护参数 B1。\n");
        indexService.indexFromPages(E2eScenarioRunner.DOC_PERM_B, null);
        assertNotNull(a);
        assertNotNull(b);
    }
}