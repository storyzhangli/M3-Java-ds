package com.zl.d43.e2e;

import com.zl.d43.e2e.BadCaseClassifier.BadCaseSignals;
import com.zl.d43.e2e.BadCaseClassifier.Classification;
import com.zl.d43.e2e.BadCaseClassifier.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D43 P4 / C13：六阶段分类器手算夹具 T1–T8（fixture-truth.classifierFixtures 逐字消费）。
 * 预期主阶段=冻结 expectPrimary；T7 含 expectSecondary=[PARSE] 优先级验证；T8 人工裁决路径。
 */
class BadCaseClassifierTest {

    private final BadCaseClassifier classifier = new BadCaseClassifier();

    @Test
    @DisplayName("T1 HTTP401+AUTH_FORBIDDEN → PERMISSION")
    void t1PermissionByStatusAndCode() {
        Classification c = classifier.classify(BadCaseSignals.of(401, "AUTH_FORBIDDEN"));
        assertEquals(Stage.PERMISSION, c.primaryStage());
        assertFalse(c.manualAdjudicationRequired());
    }

    @Test
    @DisplayName("T2 导入 FAILED errorCode=PARSE_FAILED → PARSE")
    void t2Parse() {
        Classification c = classifier.classify(new BadCaseSignals(200, "PARSE_FAILED",
                false, true, false, false, false, false));
        assertEquals(Stage.PARSE, c.primaryStage());
    }

    @Test
    @DisplayName("T3 解析正常但 Gold chunk 数=0 → CHUNKING")
    void t3Chunking() {
        Classification c = classifier.classify(new BadCaseSignals(200, null,
                false, false, true, false, false, false));
        assertEquals(Stage.CHUNKING, c.primaryStage());
    }

    @Test
    @DisplayName("T4 chunk 正常但 vector_chunk_index 缺行 → EMBEDDING")
    void t4Embedding() {
        Classification c = classifier.classify(new BadCaseSignals(200, null,
                false, false, false, true, false, false));
        assertEquals(Stage.EMBEDDING, c.primaryStage());
    }

    @Test
    @DisplayName("T5 入库正常 Top-K 未命中(ZERO_HITS_AFTER_THRESHOLD) → RETRIEVAL")
    void t5Retrieval() {
        Classification c = classifier.classify(new BadCaseSignals(200, "ZERO_HITS_AFTER_THRESHOLD",
                false, false, false, false, true, false));
        assertEquals(Stage.RETRIEVAL, c.primaryStage());
    }

    @Test
    @DisplayName("T6 证据检出但 INVALID_CITATION → GENERATION")
    void t6Generation() {
        Classification c = classifier.classify(new BadCaseSignals(200, "INVALID_CITATION",
                false, false, false, false, false, true));
        assertEquals(Stage.GENERATION, c.primaryStage());
    }

    @Test
    @DisplayName("T7 AUTH_* 与 PARSE 同现 → PERMISSION 主 + PARSE 次（权限优先于一切）")
    void t7PriorityPermissionFirst() {
        Classification c = classifier.classify(new BadCaseSignals(403, "AUTH_FORBIDDEN",
                false, true, false, false, false, false));
        assertEquals(Stage.PERMISSION, c.primaryStage());
        assertEquals(List.of(Stage.PARSE), c.secondaryStages());
    }

    @Test
    @DisplayName("T8 未知码 E2E_AUTH_CODE_UNKNOWN → UNCLASSIFIED+人工裁决")
    void t8UnclassifiedManualAdjudication() {
        Classification c = classifier.classify(BadCaseSignals.of(200, "E2E_AUTH_CODE_UNKNOWN"));
        assertEquals(Stage.UNCLASSIFIED, c.primaryStage());
        assertTrue(c.manualAdjudicationRequired());
        assertEquals(List.of(), c.secondaryStages());
    }

    @Test
    @DisplayName("泄漏位单信号 → PERMISSION（P0 语义入口）")
    void leakageFlagIsPermission() {
        Classification c = classifier.classify(new BadCaseSignals(200, null,
                true, false, false, false, false, false));
        assertEquals(Stage.PERMISSION, c.primaryStage());
    }

    @Test
    @DisplayName("全空信号 → UNCLASSIFIED，禁静默归类")
    void emptySignalsUnclassified() {
        Classification c = classifier.classify(BadCaseSignals.of(200, null));
        assertEquals(Stage.UNCLASSIFIED, c.primaryStage());
        assertTrue(c.manualAdjudicationRequired());
    }

    @Test
    @DisplayName("BadCaseRecord 必填校验+四态 retestStatus 校验")
    void badCaseRecordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new BadCaseRecord(
                "S05", "e2e", "", "expected", "RETRIEVAL", List.of(), "code", "path", null, "PASS"));
        assertThrows(IllegalArgumentException.class, () -> new BadCaseRecord(
                "S05", "e2e", "obs", "expected", "RETRIEVAL", List.of(), "code", "path", null, "DONE"));
        BadCaseRecord ok = new BadCaseRecord("S05", "e2e", "obs", "expected",
                "RETRIEVAL", null, "code", "path", "FIX-01", "PASS");
        assertEquals(List.of(), ok.secondaryStages());
    }
}