package com.zl.d39.hybrid.keyword;

import com.zl.d39.access.AccessPolicyService;
import com.zl.d39.access.AuthContext;
import com.zl.d39.hybrid.config.HybridProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词路权限过滤单测（C01 关键词侧 + 过滤先于 Top-N 截断）。
 *
 * <p>纯内存构造（forTesting 工厂，零 DB/零模型），scope 由 AccessPolicyService
 * 从 AuthContext 解析——与向量路共享同一谓词代数权威。</p>
 */
class KeywordSearchServiceScopeTest {

    private static final KeywordTokenizer TOKENIZER = new KeywordTokenizer("w+c+b+t");

    private static final AccessPolicyService POLICY = new AccessPolicyService();

    private static ChunkRow row(String chunkId, String content,
                                String tenant, String dept, String device) {
        return new ChunkRow(chunkId, "d39-kw-" + chunkId, "1", 0, "t-" + chunkId,
                List.of(), null, "FIXED_LENGTH", content, "ACTIVE", null, null,
                tenant, dept, device);
    }

    private static KeywordSearchService serviceWith(List<ChunkRow> rows) {
        return KeywordSearchService.forTesting(
                InvertedIndex.build(rows, TOKENIZER), TOKENIZER, new HybridProperties());
    }

    private static AuthContext user(String tenant, String dept, String... devices) {
        return new AuthContext("u1", tenant, dept, Set.of(devices), AuthContext.ROLE_USER);
    }

    @Test
    @DisplayName("USER：同词命中仅授权三元组行返回；缺失键/跨部门/跨租户行被先过滤掉")
    void scopeFilterKeepsOnlyAuthorizedRows() {
        var service = serviceWith(List.of(
                row("c1", "冷却泵压力异常 PUMP-771", "T1", "D1", "DEV-A"),
                row("c2", "冷却泵压力异常 PUMP-771", "T1", "D2", "DEV-A"),
                row("c3", "冷却泵压力异常 PUMP-771", "T2", "D1", "DEV-A"),
                row("c4", "冷却泵压力异常 PUMP-771", "T1", null, "DEV-A"),
                row("c5", "冷却泵压力异常 PUMP-771", "T1", "ALL", "ALL"),
                row("c6", "冷却泵压力异常 PUMP-771", null, null, null)));
        var scope = POLICY.scopeFor(user("T1", "D1", "DEV-A"));
        List<String> ids = service.search("PUMP-771", 50, null, null, null, scope)
                .stream().map(KeywordMatchView::chunkId).sorted().toList();
        assertEquals(List.of("c1", "c5"), ids);
    }

    @Test
    @DisplayName("scope=null：关闭态原语义，全行可见（零差异 H1）")
    void nullScopeKeepsSkeletonBehavior() {
        var service = serviceWith(List.of(
                row("c1", "冷却泵压力异常 PUMP-771", "T1", "D1", "DEV-A"),
                row("c3", "冷却泵压力异常 PUMP-771", "T2", "D1", "DEV-A")));
        assertEquals(2, service.search("PUMP-771", 50, null, null, null).size());
        assertEquals(2, service.search("PUMP-771", 50, null, null, null, null).size());
    }

    @Test
    @DisplayName("过滤先于 Top-N 截断：越权高分行不占坑，授权低分行在 topN=1 仍可见")
    void filterAppliesBeforeTopNTruncation() {
        // c1 与 c2 同文同分（同分 tie-break chunkId 升序）：c1 越权、c2 授权 → topN=1 必须返回 c2
        var service = serviceWith(List.of(
                row("c1", "冷却泵压力异常 PUMP-771", "T2", "D9", "DEV-Z"),
                row("c2", "冷却泵压力异常 PUMP-771", "T1", "D1", "DEV-A")));
        var scope = POLICY.scopeFor(user("T1", "D1", "DEV-A"));
        List<String> ids = service.search("PUMP-771", 1, null, null, null, scope)
                .stream().map(KeywordMatchView::chunkId).toList();
        assertEquals(List.of("c2"), ids);
    }

    @Test
    @DisplayName("ADMIN：租户内全行（含缺失 dept/device 行），跨租户仍拒绝（H7）")
    void adminSeesTenantWideIncludingUntaggedDeptDevice() {
        var service = serviceWith(List.of(
                row("c1", "冷却泵压力异常 PUMP-771", "T1", null, null),
                row("c2", "冷却泵压力异常 PUMP-771", "T1", "D2", "DEV-B"),
                row("c3", "冷却泵压力异常 PUMP-771", "T2", "D1", "DEV-A")));
        var scope = POLICY.scopeFor(new AuthContext("a1", "T1", "D1", Set.of(),
                AuthContext.ROLE_ADMIN));
        List<String> ids = service.search("PUMP-771", 50, null, null, null, scope)
                .stream().map(KeywordMatchView::chunkId).sorted().toList();
        assertEquals(List.of("c1", "c2"), ids);
    }

    @Test
    @DisplayName("精确码等值判定：授权用户查询含自身租户词命中；越权用户同词零命中（D2P 单测镜像）")
    void exactCodeHitForOwnerZeroHitForForeigner() {
        var service = serviceWith(List.of(
                row("own", "设备编号 D2P-771 泄压阀", "T1", "D1", "DEV-A"),
                row("foreign", "设备编号 D2P-771 泄压阀", "T2", "D1", "DEV-A")));
        var ownerScope = POLICY.scopeFor(user("T1", "D1", "DEV-A"));
        var foreignScope = POLICY.scopeFor(user("T9", "D1", "DEV-A"));
        assertEquals(1, service.search("D2P-771", 50, null, null, null, ownerScope).size());
        List<String> foreignHits = service.search("D2P-771", 50, null, null, null, foreignScope)
                .stream().map(KeywordMatchView::chunkId).toList();
        assertEquals(List.of(), foreignHits, "越权精确码必须零命中，不得出现在任何名次");
    }
}
