package com.zl.d41.knowledge.vectorstore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D40 R01 P5：受控故障注入装饰器组件测试（E1/E3 注入点语义，KP3/H5 冻结口径）。
 */
class FaultInjectingVectorStoreTest {

    /** 记录透传次数的桩 delegate（仅替代 PG 网络边界，不改变注入判定）。 */
    private static final class RecordingVectorStore implements VectorStore {

        final List<List<Document>> added = new ArrayList<>();

        final List<List<String>> deleted = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
            added.add(documents);
        }

        @Override
        public void delete(List<String> idList) {
            deleted.add(idList);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            deleted.add(List.of("FILTER:" + filterExpression));
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }

        @Override
        public String getName() {
            return "recording";
        }
    }

    @Test
    @DisplayName("T-FI-01 failAddCall=2：仅第 2 次 add 抛受控异常，前后透传")
    void addFailsExactlyOnNthCall() {
        RecordingVectorStore delegate = new RecordingVectorStore();
        FaultInjectingVectorStore store = new FaultInjectingVectorStore(delegate, 2, 0);

        store.add(List.of());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> store.add(List.of()));
        assertTrue(ex.getMessage().contains("simulated connection loss"));
        store.add(List.of());

        assertEquals(3, store.addCallCount());
        assertEquals(2, delegate.added.size());
    }

    @Test
    @DisplayName("T-FI-02 failDeleteCall=1：首次 delete(ids) 注入，计数独立于 add")
    void deleteFailsExactlyOnNthCall() {
        RecordingVectorStore delegate = new RecordingVectorStore();
        FaultInjectingVectorStore store = new FaultInjectingVectorStore(delegate, 0, 1);

        store.add(List.of());
        assertThrows(RuntimeException.class, () -> store.delete(List.of("c1")));
        store.delete(List.of("c2"));

        assertEquals(1, store.addCallCount());
        assertEquals(2, store.deleteCallCount());
        assertEquals(1, delegate.deleted.size());
    }

    @Test
    @DisplayName("T-FI-03 阈值 0=永不注入；表达式删除与检索恒透传（关闭态零行为差异）")
    void zeroThresholdsNeverInject() {
        RecordingVectorStore delegate = new RecordingVectorStore();
        FaultInjectingVectorStore store = new FaultInjectingVectorStore(delegate, 0, 0);

        store.add(List.of());
        store.delete(List.of("c1"));
        store.similaritySearch(SearchRequest.builder().query("q").build());

        assertEquals("recording", store.getName());
        assertEquals(1, delegate.added.size());
        assertEquals(1, delegate.deleted.size());
    }
}
