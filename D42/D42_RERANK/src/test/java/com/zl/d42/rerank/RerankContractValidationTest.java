package com.zl.d42.rerank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 端口契约边界（候选 0..50 / topN / 空值——RERANK_CONFIG_INVALID 前缀断言，C04）。 */
class RerankContractValidationTest {

    private static RerankCandidate cand(int rank) {
        return new RerankCandidate("c" + rank, "d" + rank, "text", rank, 0.5);
    }

    @Test
    @DisplayName("候选超 50 → CONFIG_INVALID")
    void tooManyCandidates() {
        List<RerankCandidate> cands = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            cands.add(cand(i));
        }
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new RerankRequest("q1", "query", cands, 5));
        assertTrue(ex.getMessage().startsWith(RerankReasonCode.RERANK_CONFIG_INVALID.code()));
    }

    @Test
    @DisplayName("topN 越界（0/51）与 topN>候选数 → CONFIG_INVALID")
    void topBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new RerankRequest("q1", "query", List.of(cand(1)), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RerankRequest("q1", "query", List.of(cand(1)), 51));
        assertThrows(IllegalArgumentException.class,
                () -> new RerankRequest("q1", "query", List.of(cand(1), cand(2)), 3));
    }

    @Test
    @DisplayName("空候选 + topN=5 合法（H4）")
    void emptyCandidatesValid() {
        var req = new RerankRequest("q1", "query", List.of(), 5);
        assertEquals(0, req.candidates().size());
    }

    @Test
    @DisplayName("null query / 候选字段非法 → CONFIG_INVALID")
    void nullGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new RerankRequest("q1", null, List.of(cand(1)), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RerankCandidate(null, "d", "t", 1, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new RerankCandidate("c", "d", null, 1, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new RerankCandidate("c", "d", "t", 0, 0.5));
    }

    @Test
    @DisplayName("Properties requireValid：model 空 / retry>1 / timeout 越界均 fail-fast")
    void propertiesFailFast() {
        var props = new RerankProperties();
        props.requireValid(); // 默认值合法
        props.getRemote().setModel(" ");
        var ex = assertThrows(IllegalStateException.class, props::requireValid);
        assertTrue(ex.getMessage().startsWith(RerankReasonCode.RERANK_CONFIG_INVALID.code()));
        props.getRemote().setModel("gte-rerank-v2");
        props.getRemote().setRetryMaxAttempts(2);
        assertThrows(IllegalStateException.class, props::requireValid);
        props.getRemote().setRetryMaxAttempts(1);
        props.getRemote().setTimeoutMillis(70000);
        assertThrows(IllegalStateException.class, props::requireValid);
    }
}