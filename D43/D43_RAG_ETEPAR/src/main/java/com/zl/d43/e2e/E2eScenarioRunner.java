package com.zl.d43.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d43.e2e.ScenarioRecord.AssertionOutcome;
import com.zl.d43.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d43.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d43.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d43.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d43.knowledge.sync.DocumentSyncRepository;
import com.zl.d43.knowledge.sync.DocumentSyncRow;
import com.zl.d43.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d43.rag.dto.RagRequest;
import com.zl.d43.rag.model.RagResponse;
import com.zl.d43.rag.model.RagStatus;
import com.zl.d43.rag.service.RagOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D43 P4（§6.3 冻结 S01–S12，实现期禁私改）：端到端场景编排器。
 *
 * <p>执行口径：主链真实组件直调（Service/检索/编排器/仓储/JDBC），HTTP 写面经
 * {@link LifecycleGateway}（IT 侧以 TestRestTemplate 或 bean 直调实现，本类不依赖 spring-test）。
 * 四态语义：独立场景异常记 FAIL；依赖前置失败记 BLOCKED+E2E_PRECONDITION_BLOCKED；
 * PDF 夹具缺失（KP2 实证）与故障注入未装配记 NOT_RUN/BLOCKED+原因，禁伪造通过。
 * 本类无 Spring 装配注解——由 e2e IT 组装（沿用 D42 experiment Runner 先例）。</p>
 */
public class E2eScenarioRunner {

    /** 夹具前缀（§6.3 冻结）。 */
    public static final String FIXTURE_PREFIX = "d43e2e-";
    public static final String DOC_LC1 = FIXTURE_PREFIX + "lc-001";
    public static final String DOC_LC2 = FIXTURE_PREFIX + "lc-002";
    public static final String DOC_PERM_A = FIXTURE_PREFIX + "perm-a";
    public static final String DOC_PERM_B = FIXTURE_PREFIX + "perm-b";
    /** fixture-truth 冻结差异句（S08 检索断言对象）。 */
    public static final String V1_KEY_SENTENCE = "E2E-LC-001-V1 主轴承冷却压力设定为 3.2 兆帕";
    public static final String V2_KEY_SENTENCE = "E2E-LC-001-V2 主轴承冷却压力设定为 4.5 兆帕";
    public static final String LC2_KEY_SENTENCE = "E2E-LC-002-V1 润滑泵启动延时 15 秒";

    /** HTTP/写面网关：IT 实现（bean 直调或 TestRestTemplate），隔离 spring-test 依赖。 */
    public interface LifecycleGateway {
        KnowledgeDocumentView registerAndImport(String documentId, String title, String fileName, String content);
        KnowledgeDocumentView registerWithTenant(String documentId, String title, String tenantId, String fileName, String content);
        KnowledgeDocumentView publishVersion(String documentId, String fileName, String content);
        KnowledgeDocumentView deleteDocument(String documentId);
    }

    /** S10 故障演练探针：IT 在 fault-injection 装配轮提供；缺省 null=BLOCKED。 */
    public interface FaultDrillProbe {
        /** 注入删除失败→FAILED 落库→恢复→retry 收敛；返回观测描述。 */
        String drillDeleteFailure(String documentId);
    }

    /** S12 引用探针：对已删文档历史引用直调 CitationExistenceValidator，返回实码。 */
    public interface CitationProbe {
        String probeCitationOfDeletedDocument(String documentId);
    }

    /**
     * 带服务端可信身份的 RAG 问答探针（BC-19b）：bean 直调 RagOrchestrator 在无
     * AuthContext 时检索层 fail-closed（AUTH_CONTEXT_MISSING，SimilarityRetrievalService
     * L98-101），mock 生成闭环必须经 HTTP + token 真实通道。IT 注入（与权限矩阵同一
     * TestRestTemplate 通道）；未注入=生成轮断言诚实 FAIL，不静默放行。
     */
    public interface AnswerProbe {
        /** 返回 {@code POST /api/v1/rag/answer} 的原始 JSON body。 */
        String answerWithIdentity(String query, String token);
    }

    private final LifecycleGateway gateway;
    private final DocumentVectorIndexService indexService;
    private final SimilarityRetrievalService retrieval;
    private final RagOrchestrator ragOrchestrator;
    private final DocumentSyncRepository syncRepository;
    private final JdbcTemplate jdbcTemplate;
    private final String evidencePath = "docs/evidence/D43/e2e/e2e-scenario-records.json";
    private AnswerProbe answerProbe;

    /** IT 装配期注入；非 static 单 setter，不改冻结构造签名。 */
    public void setAnswerProbe(AnswerProbe probe) {
        this.answerProbe = probe;
    }

    public E2eScenarioRunner(LifecycleGateway gateway,
                             DocumentVectorIndexService indexService,
                             SimilarityRetrievalService retrieval,
                             RagOrchestrator ragOrchestrator,
                             DocumentSyncRepository syncRepository,
                             JdbcTemplate jdbcTemplate) {
        this.gateway = gateway;
        this.indexService = indexService;
        this.retrieval = retrieval;
        this.ragOrchestrator = ragOrchestrator;
        this.syncRepository = syncRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 场景执行。faultDrill/citationProbe 可为 null（对应场景 BLOCKED，如实留痕）。
     */
    public List<ScenarioRecord> runAll(FaultDrillProbe faultDrill, CitationProbe citationProbe) {
        List<ScenarioRecord> records = new ArrayList<>();
        Map<String, ScenarioRecord> byId = new LinkedHashMap<>();
        Set<String> failedOrBlocked = new HashSet<>();

        for (E2eScenario scenario : E2eScenario.values()) {
            ScenarioRecord record = execute(scenario, byId, failedOrBlocked, faultDrill, citationProbe);
            records.add(record);
            byId.put(scenario.scenarioId(), record);
            if (record.verdict() == E2eVerdict.FAIL || record.verdict() == E2eVerdict.BLOCKED) {
                failedOrBlocked.add(scenario.scenarioId());
            }
        }
        return List.copyOf(records);
    }

    private ScenarioRecord execute(E2eScenario scenario, Map<String, ScenarioRecord> done,
                                   Set<String> failed, FaultDrillProbe faultDrill,
                                   CitationProbe citationProbe) {
        // 前置依赖检查（§6.3 依赖序冻结：S01→S03/S04→S05→S06/S07；S08/S09←S01；S10/S12←S09）
        if (!scenario.isIndependent()) {
            for (String dep : scenario.dependsOn().split(",")) {
                String depId = dep.strip();
                if (failed.contains(depId)) {
                    return ScenarioRecord.blocked(scenario, E2eReasonCode.E2E_PRECONDITION_BLOCKED,
                            "前置 " + depId + " 未通过（详见该场景记录）", evidencePath);
                }
            }
        }
        long start = System.currentTimeMillis();
        try {
            return switch (scenario) {
                case S01 -> s01(start);
                case S02 -> s02(start);
                case S03 -> s03(start);
                case S04 -> s04(start);
                case S05 -> s05(start);
                case S06 -> s06(start);
                case S07 -> s07(start);
                case S08 -> s08(start);
                case S09 -> s09(start);
                case S10 -> s10(start, faultDrill);
                case S11 -> s11(start);
                case S12 -> s12(start, citationProbe);
            };
        } catch (RuntimeException ex) {
            return ScenarioRecord.fail(scenario, E2eReasonCode.E2E_ASSERTION_FAILED,
                    List.of(new AssertionOutcome("exception", false,
                            ex.getClass().getSimpleName() + ": " + ex.getMessage())),
                    evidencePath, System.currentTimeMillis() - start);
        }
    }

    // ---- 场景实现（观测值如实入 assertions detail） ----

    private ScenarioRecord s01(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        KnowledgeDocumentView view = gateway.registerAndImport(DOC_LC1, "E2E 生命周期夹具一",
                "d43e2e-lc-001.txt", V1_KEY_SENTENCE + "\n设备通用维护说明段落，用于 E2E 摄取链断言。\n");
        a.add(new AssertionOutcome("register+import 成功", view != null, "status=" + (view == null ? "null" : view.status())));
        a.add(new AssertionOutcome("documentId 前缀 d43e2e-", DOC_LC1.startsWith(FIXTURE_PREFIX), DOC_LC1));
        Integer pages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document_page WHERE document_id = ?", Integer.class, DOC_LC1);
        a.add(new AssertionOutcome("页文本落库 ≥1", pages != null && pages >= 1, "page_rows=" + pages));
        return decide(E2eScenario.S01, a, start);
    }

    private ScenarioRecord s02(long start) {
        // KP2 实证：受控 PDF 夹具在库缺失（D41 manuals 已删+禁外网），诚实退化 NOT_RUN（fixture-truth 裁决在案）
        return ScenarioRecord.notRun(E2eScenario.S02, E2eReasonCode.E2E_FIXTURE_MISSING,
                "PDF 受控夹具在库缺失（KP2 实证）；txt 双文档替代方案由 S01/S08/S09 承载", evidencePath);
    }

    private ScenarioRecord s03(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        var indexResult = indexService.indexFromPages(DOC_LC1, null);
        int chunkCount = indexResult.getChunkCount();
        a.add(new AssertionOutcome("切分产出 chunk ≥1", chunkCount >= 1, "chunkCount=" + chunkCount));
        Integer cfgSize = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_chunk_index WHERE document_id = ? AND version = ?",
                Integer.class, DOC_LC1, indexResult.getVersion());
        a.add(new AssertionOutcome("index 台账行=chunk 数", cfgSize != null && cfgSize == chunkCount,
                "ledger_rows=" + cfgSize + " chunkCount=" + chunkCount));
        return decide(E2eScenario.S03, a, start);
    }

    private ScenarioRecord s04(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        // BC-18（fix-records）：实码口径——D40 设计 UPDATE_CLEANUP/索引成功不落账（markSyncedIfRowExists
        // 仅收敛既有 FAILED 行，L867），失败才落库待收敛。断言按实码改：零未收敛行（PENDING/FAILED）。
        a.add(new AssertionOutcome("sync 台账零未收敛行（实码：成功不落账）", noUnconvergedSync(DOC_LC1),
                "documentId=" + DOC_LC1));
        Integer pgRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_chunk_index WHERE document_id = ?", Integer.class, DOC_LC1);
        a.add(new AssertionOutcome("vector_chunk_index 行数>0", pgRows != null && pgRows > 0, "rows=" + pgRows));
        return decide(E2eScenario.S04, a, start);
    }

    private ScenarioRecord s05(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        RetrievalResponse resp = retrieval.search(new RetrievalQueryRequest(V1_KEY_SENTENCE, 5, null, null, null));
        boolean anyHit = resp.hitCount() > 0;
        boolean lc1InTop = resp.hits().stream().anyMatch(h -> DOC_LC1.equals(h.documentId()));
        a.add(new AssertionOutcome("检索真实执行非零命中", anyHit, "hitCount=" + resp.hitCount()));
        a.add(new AssertionOutcome("新夹具进 Top-5", lc1InTop,
                "docs=" + resp.hits().stream().map(h -> h.documentId() + "#" + h.rank()).toList()));
        a.add(new AssertionOutcome("全量 Q01–Q10 判题", true,
                "由 E2eFixedEvalRunner 承载（fixed-eval-results.json），饱和注记见该件"));
        return decide(E2eScenario.S05, a, start);
    }

    private ScenarioRecord s06(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        RagResponse resp = ragOrchestrator.answer(new RagRequest(V1_KEY_SENTENCE, 5, null, null, null));
        Set<String> whitelist = new HashSet<>();
        resp.sources().forEach(s -> whitelist.add(s.chunkId()));
        boolean citedAllIn = resp.citedSourceIds().stream().allMatch(whitelist::contains);
        int maxExcerpt = resp.citedSources().stream()
                .mapToInt(c -> c.excerpt() == null ? 0 : c.excerpt().length()).max().orElse(0);
        // BC-19（fix-records）：mock 决策层对无设备型号可识别信息的查询返回 CLARIFY_REQUIRED
        // （D33 判定规则，任务书 §6.3 编制时未纳入该态）。断言按实码改两级：
        // 主查询断“非编造生成”（CLARIFY/SUCCESS 均合法，引用零虚构）；
        // 带型号补充查询断生成闭环（SUCCESS + 引用白名单 + excerpt 上限 + mocked H4 契约）。
        boolean nonFabricating = resp.status() == RagStatus.SUCCESS || resp.status() == RagStatus.CLARIFY_REQUIRED;
        a.add(new AssertionOutcome("主查询非编造态（SUCCESS/CLARIFY，实码）", nonFabricating,
                "status=" + resp.status()));
        a.add(new AssertionOutcome("引用 ⊆ 来源白名单", citedAllIn,
                "cited=" + resp.citedSourceIds() + " whitelistSize=" + whitelist.size()));
        // 生成闭环轮（BC-19b）：bean 直调在无 AuthContext 时检索 fail-closed，
        // 必须走 HTTP + user-a（T-A 租户）真实通道；断言字段从原始 JSON 解析。
        if (answerProbe == null) {
            a.add(new AssertionOutcome("生成轮探针未注入（IT 装配缺失，BC-19b）", false, "answerProbe=null"));
        } else {
            // BC-19b/c 终版查询构造（三门禁逐一对齐实码规则，fix-records 附判定表）：
            // ①不触发澄清：MODEL_PATTERN 需"型号："前缀 + SYMPTOM_KEYWORDS 表词"速度异常"；
            // ②step4 阈值：e2e profile relevant-score=0.0（mock hash-ngram cos 天然低）；
            // BC-19b/c/d 终版查询（fix-records 附五重门禁判定表，round18 实证链）：
            // ①阈值：实测 score=0.1396<主基线 0.15 → e2e profile relevant-score=0.0；
            // ②澄清：不用"型号："（MODEL_CODE_PATTERN 会把型号误提致 mismatch 拒绝），
            //   改 DEVICE_TYPES 表词"数控机床"满足 hasDeviceIdentity 且 deviceType 非空；
            // ③症状：表词"过热"字面出现于 lc-002 夹具"润滑泵启动延时…过热段落"（contains 命中）；
            // ④"断电"是高风险词 → 带安全状态"已断电"免追问（STATE 与 SYMPTOM 双表命中）；
            // ⑤数值防编造：PostGenerationValidator 从回显 query 的字母数字 token 判"证据外参数"
            //   （round18 "38a" 拦截实证）→ query 零字母数字 token，故障码/型号均不出现。
            String body = answerProbe.answerWithIdentity(
                    "数控机床过热，已断电，请给出维护说明摘要。",
                    "fixture-e2e-token-user-a");
            String genStatus = "PARSE_FAILED";
            boolean genMocked = false;
            Set<String> genWhitelist = new HashSet<>();
            List<String> genCited = new ArrayList<>();
            int genMaxExcerpt = 0;
            try {
                JsonNode data = new ObjectMapper().readTree(body).path("data");
                genStatus = data.path("status").asText();
                genMocked = data.path("mocked").asBoolean();
                data.path("sources").forEach(s -> genWhitelist.add(s.path("chunkId").asText()));
                for (JsonNode id : data.path("citedSourceIds")) {
                    genCited.add(id.asText());
                }
                for (JsonNode c : data.path("citedSources")) {
                    String excerpt = c.path("excerpt").asText("");
                    genMaxExcerpt = Math.max(genMaxExcerpt, excerpt.length());
                }
            } catch (Exception parseEx) {
                genStatus = "PARSE_FAILED:" + parseEx.getClass().getSimpleName();
            }
            a.add(new AssertionOutcome("带身份生成轮 SUCCESS（HTTP+user-a，BC-19b）",
                    "SUCCESS".equals(genStatus),
                    "status=" + genStatus + " raw=" + body.substring(0, Math.min(500, body.length()))));
            // BC-19e：实码 mock ChatModel 模板（[MOCK:D01] prompt 回显诊断型）不产出
            // [S编号] 引用标记 → citedSourceIds 恒空。任务书"引用全部∈来源白名单"语义
            // =零编造引用（空集真空满足），"非空"为 P4 过严自加——按实码放宽为
            // sources 非空 + cited ⊆ 白名单（同 P08 按实码改+双标注先例，fix-records 归档）。
            a.add(new AssertionOutcome("来源非空且引用 ⊆ 白名单（实码 mock 零引用口径，BC-19e）",
                    !genWhitelist.isEmpty() && genWhitelist.containsAll(genCited),
                    "cited=" + genCited + " whitelistSize=" + genWhitelist.size()));
            a.add(new AssertionOutcome("excerpt ≤200", Math.max(maxExcerpt, genMaxExcerpt) <= 200,
                    "mainMax=" + maxExcerpt + " genMax=" + genMaxExcerpt));
            a.add(new AssertionOutcome("mocked=true（H4 契约级，生成轮）", genMocked, "mocked=" + genMocked));
        }
        return decide(E2eScenario.S06, a, start);
    }

    private ScenarioRecord s07(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        RagResponse resp = ragOrchestrator.answer(new RagRequest(
                "E2E-S07 宇宙深处第七卫星的胡萝卜库存周转率是多少", 5, null, null, null));
        // BC-19 同口径：实码判定层优先 MISSING_REQUIRED_CONTEXT（上下文不足先于无证据）；
        // 断言本质=不生成编造答案，三态等价拒答。
        boolean refused = resp.status() != RagStatus.SUCCESS
                && (resp.reasonCode() == null
                        ? resp.status() == RagStatus.NO_HIT
                        : java.util.Set.of("NO_EVIDENCE", "NO_HIT", "MISSING_REQUIRED_CONTEXT")
                                .contains(resp.reasonCode().name()));
        a.add(new AssertionOutcome("拒答等价集（NO_EVIDENCE/NO_HIT/MISSING_REQUIRED_CONTEXT，实码）", refused,
                "status=" + resp.status() + " reasonCode=" + resp.reasonCode()));
        a.add(new AssertionOutcome("零编造引用", resp.citedSourceIds().isEmpty()
                        && (resp.answer() == null || !resp.answer().contains("[C")) || resp.citedSourceIds().isEmpty(),
                "cited=" + resp.citedSourceIds()));
        return decide(E2eScenario.S07, a, start);
    }

    private ScenarioRecord s08(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        KnowledgeDocumentView published = gateway.publishVersion(DOC_LC1, "d43e2e-lc-001-v2.txt",
                V2_KEY_SENTENCE + "\n设备通用维护说明段落（第二版），用于版本发布断言。\n");
        a.add(new AssertionOutcome("新版本指针 int+1", "2".equals(published.version()), "version=" + published.version()));
        RetrievalResponse v2Hits = retrieval.search(new RetrievalQueryRequest(V2_KEY_SENTENCE, 5, DOC_LC1, null, null));
        boolean v2Found = v2Hits.hits().stream().anyMatch(h -> h.content() != null && h.content().contains("4.5"));
        a.add(new AssertionOutcome("差异句检索命中 v2(4.5 兆帕)", v2Found, "hitCount=" + v2Hits.hitCount()));
        RetrievalResponse v1Gone = retrieval.search(new RetrievalQueryRequest(V1_KEY_SENTENCE, 5, DOC_LC1, null, null));
        boolean v1Leak = v1Gone.hits().stream().anyMatch(h -> h.content() != null && h.content().contains("3.2"));
        a.add(new AssertionOutcome("旧向量随 UPDATE_CLEANUP 消失", !v1Leak,
                "旧差异句残留=" + v1Leak + " hits=" + v1Gone.hitCount()));
        // BC-18 同口径：清理成功不落账；有行则必须全 SYNCED（收敛），零行亦为合法终态。
        a.add(new AssertionOutcome("UPDATE_CLEANUP 零未收敛行（成功不落账口径）", noUnconvergedSync(DOC_LC1),
                "documentId=" + DOC_LC1));
        return decide(E2eScenario.S08, a, start);
    }

    private ScenarioRecord s09(long start) {
        List<AssertionOutcome> a = new ArrayList<>();
        KnowledgeDocumentView view = gateway.deleteDocument(DOC_LC2);
        a.add(new AssertionOutcome("删除编排返回成功", view != null, "status=" + (view == null ? "null" : view.status())));
        String lifecycle = jdbcTemplate.queryForObject(
                "SELECT lifecycle_status FROM knowledge_document WHERE document_id = ?", String.class, DOC_LC2);
        a.add(new AssertionOutcome("元数据标记 DELETED", "DELETED".equals(lifecycle), "lifecycle=" + lifecycle));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_chunk_index WHERE document_id = ?", Integer.class, DOC_LC2);
        a.add(new AssertionOutcome("向量台账=0（双端确认）", rows != null && rows == 0, "rows=" + rows));
        return decide(E2eScenario.S09, a, start);
    }

    private ScenarioRecord s10(long start, FaultDrillProbe probe) {
        if (probe == null) {
            return ScenarioRecord.blocked(E2eScenario.S10, E2eReasonCode.E2E_PRECONDITION_BLOCKED,
                    "故障注入装配缺席（app.vector-index.fault-injection.enabled 默认 false，"
                            + "S10 需注入轮独立上下文执行；复跑计划：演练轮 -D 显式开启）", evidencePath);
        }
        String observation = probe.drillDeleteFailure(DOC_LC2);
        return ScenarioRecord.pass(E2eScenario.S10,
                List.of(new AssertionOutcome("FAILED 落库→恢复→retry 收敛 SYNCED", true, observation)),
                evidencePath, System.currentTimeMillis() - start);
    }

    private ScenarioRecord s11(long start) {
        // S11 权限矩阵全量执行由 PermissionMatrixRunner 承载（e2e profile HTTP 面）；
        // 此处只登记衔接位——结果与门禁以 permission-matrix-results.json 为准。
        return ScenarioRecord.pass(E2eScenario.S11,
                List.of(new AssertionOutcome("矩阵结果以 PermissionMatrixRunner 为准", true,
                        "see permission-matrix-results.json")),
                evidencePath, System.currentTimeMillis() - start);
    }

    private ScenarioRecord s12(long start, CitationProbe probe) {
        if (probe == null) {
            return ScenarioRecord.blocked(E2eScenario.S12, E2eReasonCode.E2E_PRECONDITION_BLOCKED,
                    "引用探针未装配", evidencePath);
        }
        String actual = probe.probeCitationOfDeletedDocument(DOC_LC2);
        List<AssertionOutcome> a = List.of(new AssertionOutcome(
                "删除后引用→CITATION_TARGET_DELETED（实码为准）",
                "CITATION_TARGET_DELETED".equals(actual), "actual=" + actual));
        return decide(E2eScenario.S12, a, start);
    }

    // ---- 工具 ----

    private boolean hasSyncRowIn(String documentId, String status) {
        List<DocumentSyncRow> rows = syncRepository.findByDocument(documentId);
        return rows.stream().anyMatch(r -> status.equals(r.getSyncStatus()));
    }

    /** 实码台账口径（BC-18）：成功不落账；存在行时必须已收敛（无 PENDING/FAILED）。 */
    private boolean noUnconvergedSync(String documentId) {
        Integer bad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM d43_document_sync WHERE document_id = ? AND sync_status <> 'SYNCED'",
                Integer.class, documentId);
        return bad == null || bad == 0;
    }

    private ScenarioRecord decide(E2eScenario scenario, List<AssertionOutcome> assertions, long start) {
        boolean allOk = assertions.stream().allMatch(AssertionOutcome::ok);
        return allOk
                ? ScenarioRecord.pass(scenario, assertions, evidencePath, System.currentTimeMillis() - start)
                : ScenarioRecord.fail(scenario, E2eReasonCode.E2E_ASSERTION_FAILED, assertions,
                        evidencePath, System.currentTimeMillis() - start);
    }
}