package com.zl.d42.knowledge.table;

import com.zl.d42.knowledge.table.dto.TableExtractSummary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R02-04 服务层原子替换语义单测（fake 仓储记录调用序列，零 DB 依赖；
 * 真实 MySQL 事务/回滚/隔离验证见 TableRecordPersistenceIntegrationTest）。
 */
class TableRecordServiceReplaceSemanticsTest {

    /** 调用记录型 fake 仓储：不触库，断言"写序"与"失败零触碰"。 */
    private static final class RecordingRepository extends TableRecordRepository {
        final List<String> calls = new ArrayList<>();
        int extractCalls;
        int rowCalls;

        RecordingRepository() {
            super(null);
        }

        @Override
        Long upsertExtract(String documentId, int version, int pageNumber, int tableIndex,
                           String headerSnapshotJson, int rowCount, int colCount,
                           Integer sourceLineStart, Integer sourceLineEnd,
                           String detectFlags, String confidence,
                           Long continuationOfTableId, String status) {
            calls.add("upsertExtract");
            extractCalls++;
            return (long) extractCalls;
        }

        @Override
        void upsertRow(long tableId, int rowIndex, String cellsJson,
                       Integer sourceLineNumber, String recordHash) {
            calls.add("upsertRow");
            rowCalls++;
        }

        @Override
        int deleteByDocumentVersion(String documentId, int version) {
            calls.add("deleteByDocumentVersion");
            return 0;
        }

        @Override
        void updateLinkageFlags(long tableId, String detectFlags) {
            calls.add("updateLinkageFlags");
        }

        @Override
        List<Map<String, Object>> findTables(String documentId, String version, boolean activeOnly) {
            calls.add("findTables");
            return List.of();
        }

        @Override
        List<Map<String, Object>> findChunksForLinkage(String documentId, String version) {
            return List.of();
        }
    }

    private static TableRecordService service(RecordingRepository repository) {
        return new TableRecordService(repository, null, new TableProperties());
    }

    private static final String TABLE_TEXT = """
            | Code | Value | Note |
            |------|-------|------|
            | E-101 | 12.5 mm | ok |
            | E-102 | 380V | warn |
            """;

    /** 场景5（解析失败）：PDF 字节损坏 → PARSE_FAILED，不删不写，旧数据不受触碰。 */
    @Test
    void parseFailureTouchesNothing() {
        RecordingRepository repository = new RecordingRepository();
        TableExtractSummary summary = service(repository).extractAndPersist(
                "d42r01-doc-a", "1", "pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8),
                null, null);
        assertTrue(summary.degradedCodes().contains(TableDegrade.PARSE_FAILED),
                "PARSE_FAILED expected, got " + summary.degradedCodes());
        assertFalse(repository.calls.contains("deleteByDocumentVersion"),
                "failure must not clear: " + repository.calls);
        assertEquals(0, repository.extractCalls);
        assertEquals(0, repository.rowCalls);
    }

    /** 场景4（成功解析为空）：文本无表格 → 必须清理旧域（delete 先于任何写入）。 */
    @Test
    void successfulEmptyResultClearsOldData() {
        RecordingRepository repository = new RecordingRepository();
        TableExtractSummary summary = service(repository).extractAndPersist(
                "d42r01-doc-a", "1", "txt", "纯散文没有表格".getBytes(StandardCharsets.UTF_8),
                null, "纯散文没有表格");
        assertFalse(summary.degradedCodes().contains(TableDegrade.PARSE_FAILED));
        assertEquals(List.of("deleteByDocumentVersion", "findTables"), repository.calls,
                "must clear then relink only, got " + repository.calls);
    }

    /** 原子替换写序：delete → insert(表/行) → 关联重算；重复抽取不产生重复记录。 */
    @Test
    void atomicReplaceDeletesBeforeInserts() {
        RecordingRepository repository = new RecordingRepository();
        TableRecordService service = service(repository);
        service.extractAndPersist("d42r01-doc-a", "1", "txt",
                TABLE_TEXT.getBytes(StandardCharsets.UTF_8), null, TABLE_TEXT);
        assertEquals("deleteByDocumentVersion", repository.calls.get(0));
        assertTrue(repository.calls.indexOf("upsertExtract") > 0);
        assertTrue(repository.rowCalls >= 2);
        int extracts = repository.extractCalls;
        int rows = repository.rowCalls;
        // 重抽同内容：delete-first 全量替换（幂等），记录数与首轮一致不翻倍
        RecordingRepository second = new RecordingRepository();
        TableExtractSummary summary = service(second).extractAndPersist(
                "d42r01-doc-a", "1", "txt",
                TABLE_TEXT.getBytes(StandardCharsets.UTF_8), null, TABLE_TEXT);
        assertEquals(extracts, second.extractCalls);
        assertEquals(rows, second.rowCalls);
        assertEquals(rows, summary.rows(), "rowCount reported");
        assertEquals("deleteByDocumentVersion", second.calls.get(0));
    }

    /** 版本非数字串：既有 VERSION_NOT_PERSISTABLE 语义不回退，零触碰。 */
    @Test
    void nonNumericVersionNoTouch() {
        RecordingRepository repository = new RecordingRepository();
        TableExtractSummary summary = service(repository).extractAndPersist(
                "d42r01-doc-a", "v1", "txt", TABLE_TEXT.getBytes(StandardCharsets.UTF_8),
                null, TABLE_TEXT);
        assertTrue(summary.degradedCodes().contains(TableDegrade.VERSION_NOT_PERSISTABLE));
        assertTrue(repository.calls.isEmpty(), "no DB calls expected: " + repository.calls);
    }

    /** docx：成功态+退化记录（非失败），仍走原子替换清理旧域。 */
    @Test
    void docxDegradedIsParsedSuccessNotFailure() {
        RecordingRepository repository = new RecordingRepository();
        TableExtractSummary summary = service(repository).extractAndPersist(
                "d42r01-doc-a", "1", "docx", "x".getBytes(StandardCharsets.UTF_8), null, null);
        assertFalse(summary.degradedCodes().contains(TableDegrade.PARSE_FAILED));
        assertTrue(summary.degradedCodes().contains(TableDegrade.DOCX_TABLE_NOT_STRUCTURED));
        assertTrue(repository.calls.contains("deleteByDocumentVersion"));
        assertEquals(1, repository.extractCalls);
    }
}
