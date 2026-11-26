package com.zl.d43.knowledge.table;

import com.zl.d43.knowledge.table.dto.TableExtractSummary;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R02-04 真实 MySQL 集成验证（任务书执行纪律4：不得仅以 Mock 宣称数据库事务已验证）。
 *
 * <p>连接参数与工程 application.yml / DbExec 同源（127.0.0.1:3306/ai_db，
 * MYSQL_PASSWORD 经环境变量注入，绝不落盘回显）。测试数据全部使用
 * {@code d43r01-doc-} 独立前缀，@AfterAll 限定前缀清理 + 全库表格域指纹复原核验，
 * 不触碰 D37/D39/D40 与其他资产。MySQL 不可用时整类 assumption 跳过（如实 NOT RUN）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableRecordPersistenceIntegrationTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TableRecordService service;
    private static long baselineExtracts;
    private static long baselineRows;

    private final String docA = "d43r01-doc-" + UUID.randomUUID().toString().replace("-", "");
    private final String docB = "d43r01-doc-" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    void setUp() {
        String password = System.getenv("MYSQL_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "MYSQL_PASSWORD 未注入：真实 MySQL 集成 NOT RUN");
        String url = "jdbc:mysql://127.0.0.1:3306/ai_db?useUnicode=true&characterEncoding=utf8";
        String user = System.getenv().getOrDefault("MYSQL_USER", "root");
        try (Connection probe = DriverManager.getConnection(url, user, password)) {
            assertNotNull(probe);
        } catch (Exception ex) {
            Assumptions.assumeTrue(false, "MySQL 不可达：" + ex + "（真实集成 NOT RUN）");
        }
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(user);
        source.setPassword(password);
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new TableRecordService(new TableRecordRepository(jdbc), null, new TableProperties());
        baselineExtracts = count("SELECT COUNT(*) FROM d43_table_extract");
        baselineRows = count("SELECT COUNT(*) FROM d43_table_row");
    }

    @AfterAll
    void cleanupAndFingerprint() {
        if (jdbc == null) {
            return;
        }
        int rows = jdbc.update("DELETE tr FROM d43_table_row tr JOIN d43_table_extract te"
                + " ON tr.table_id = te.id WHERE te.document_id LIKE 'd43r01-doc-%'");
        int tables = jdbc.update("DELETE FROM d43_table_extract WHERE document_id LIKE 'd43r01-doc-%'");
        System.out.println("R01-IT cleanup tables=" + tables + " rows=" + rows);
        assertEquals(baselineExtracts, count("SELECT COUNT(*) FROM d43_table_extract"),
                "表格域指纹必须复原基线");
        assertEquals(baselineRows, count("SELECT COUNT(*) FROM d43_table_row"),
                "行域指纹必须复原基线");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String tableText(String firstValue, int repeats) {
        StringBuilder builder = new StringBuilder("""
                | Code | Value | Note |
                |------|-------|------|
                """);
        for (int i = 1; i <= repeats; i++) {
            builder.append("| E-10").append(i).append(" | ").append(firstValue)
                    .append(" | note").append(i).append(" |\n");
        }
        return builder.toString();
    }

    private long tableCount(String documentId, String version) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM d43_table_extract"
                + " WHERE document_id = ? AND version = ?", Long.class, documentId,
                Integer.parseInt(version));
        return value == null ? 0 : value;
    }

    private long rowCount(String documentId, String version) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM d43_table_row tr"
                + " JOIN d43_table_extract te ON tr.table_id = te.id"
                + " WHERE te.document_id = ? AND te.version = ?", Long.class,
                documentId, Integer.parseInt(version));
        return value == null ? 0 : value;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 中途写入失败注入仓储：第 failFromRow 次 upsertRow 抛异常。 */
    private static final class FaultyRepository extends TableRecordRepository {
        private final JdbcTemplate delegate;
        private final int failFromRow;
        private int rowCalls;

        FaultyRepository(JdbcTemplate delegate, int failFromRow) {
            super(delegate);
            this.delegate = delegate;
            this.failFromRow = failFromRow;
        }

        @Override
        Long upsertExtract(String documentId, int version, int pageNumber, int tableIndex,
                           String headerSnapshotJson, int rowCount, int colCount,
                           Integer sourceLineStart, Integer sourceLineEnd,
                           String detectFlags, String confidence,
                           Long continuationOfTableId, String status) {
            return new TableRecordRepository(delegate).upsertExtract(documentId, version,
                    pageNumber, tableIndex, headerSnapshotJson, rowCount, colCount,
                    sourceLineStart, sourceLineEnd, detectFlags, confidence,
                    continuationOfTableId, status);
        }

        @Override
        void upsertRow(long tableId, int rowIndex, String cellsJson,
                       Integer sourceLineNumber, String recordHash) {
            rowCalls++;
            if (rowCalls == failFromRow) {
                throw new IllegalStateException("injected DB write failure");
            }
            new TableRecordRepository(delegate).upsertRow(tableId, rowIndex, cellsJson,
                    sourceLineNumber, recordHash);
        }
    }

    // ------------------------------------------------------------------
    // 场景矩阵（任务书 R02-04 必须新增测试表）
    // ------------------------------------------------------------------

    /** 场景1 行数减少：5 行 → 3 行，旧 row_index=3/4 必须清除。 */
    @Test
    void rowCountReductionRemovesStaleRows() {
        TableExtractSummary first = service.extractAndPersist(docA, "1", "txt",
                utf8(tableText("12.5 mm", 5)), null, tableText("12.5 mm", 5));
        assertEquals(5, first.rows());
        assertEquals(5, rowCount(docA, "1"));
        service.extractAndPersist(docA, "1", "txt",
                utf8(tableText("12.5 mm", 3)), null, tableText("12.5 mm", 3));
        assertEquals(3, rowCount(docA, "1"), "stale row_index 3/4 must be gone");
        assertEquals(1, tableCount(docA, "1"));
    }

    /** 场景2 表格数量减少：2 张表 → 1 张表，旧表及旧行整体清除。 */
    @Test
    void tableCountReductionRemovesStaleTables() {
        String twoTables = tableText("1 mm", 2) + "\n正文间隔行\n\n" + tableText("2 mm", 1);
        TableRecordService fresh = new TableRecordService(
                new TableRecordRepository(jdbc), null, new TableProperties());
        fresh.extractAndPersist(docA, "1", "txt", utf8(twoTables), null, twoTables);
        long before = tableCount(docA, "1");
        assertTrue(before >= 2, "expected >=2 tables, got " + before);
        fresh.extractAndPersist(docA, "1", "txt",
                utf8(tableText("3 mm", 1)), null, tableText("3 mm", 1));
        assertEquals(1, tableCount(docA, "1"), "stale table must be gone");
    }

    /** 场景3 成功解析为空：有表格 → 无表格文本，旧表格全部清理（区别于解析失败）。 */
    @Test
    void successfulEmptyParseClearsAll() {
        service.extractAndPersist(docA, "1", "txt", utf8(tableText("1 mm", 2)),
                null, tableText("1 mm", 2));
        assertTrue(rowCount(docA, "1") > 0);
        String prose = "这里只有散文，没有任何管道表。";
        service.extractAndPersist(docA, "1", "txt", utf8(prose), null, prose);
        assertEquals(0, tableCount(docA, "1"));
        assertEquals(0, rowCount(docA, "1"));
    }

    /** 场景4 重复抽取：同内容两次，不产生重复记录。 */
    @Test
    void repeatExtractionNoDuplicates() {
        String text = tableText("9 mm", 4);
        service.extractAndPersist(docA, "1", "txt", utf8(text), null, text);
        long tables = tableCount(docA, "1");
        long rows = rowCount(docA, "1");
        service.extractAndPersist(docA, "1", "txt", utf8(text), null, text);
        assertEquals(tables, tableCount(docA, "1"));
        assertEquals(rows, rowCount(docA, "1"));
    }

    /** 场景5 解析失败：有效旧数据 + PDF 损坏字节重抽 → PARSE_FAILED 且旧数据完整保留。 */
    @Test
    void parseFailureKeepsValidOldData() {
        String text = tableText("8 mm", 3);
        service.extractAndPersist(docA, "1", "txt", utf8(text), null, text);
        long rows = rowCount(docA, "1");
        assertTrue(rows > 0);
        TableExtractSummary failed = service.extractAndPersist(docA, "1", "pdf",
                utf8("this is definitely not a PDF file"), null, null);
        assertTrue(failed.degradedCodes().contains(TableDegrade.PARSE_FAILED),
                "PARSE_FAILED expected: " + failed.degradedCodes());
        assertEquals(rows, rowCount(docA, "1"), "failure must not touch valid data");
    }

    /** 场景6 数据库中途写入失败：事务回滚，旧数据完整（半套数据禁止存在）。 */
    @Test
    void midWriteFailureRollsBackAtomically() {
        String oldText = tableText("7 mm", 3);
        service.extractAndPersist(docA, "1", "txt", utf8(oldText), null, oldText);
        long rowsBefore = rowCount(docA, "1");
        String hashBefore = jdbc.queryForObject("SELECT MIN(record_hash) FROM d43_table_row tr"
                + " JOIN d43_table_extract te ON tr.table_id=te.id"
                + " WHERE te.document_id=? AND te.version=?", String.class, docA, 1);
        FaultyRepository faulty = new FaultyRepository(jdbc, 3);
        TableRecordService faultyService = new TableRecordService(faulty, null, new TableProperties());
        String newText = tableText("7 mm", 6);
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                txTemplate.execute(status -> faultyService.extractAndPersist(
                        docA, "1", "txt", utf8(newText), null, newText)));
        assertTrue(thrown.getMessage().contains("injected DB write failure"), thrown.toString());
        assertEquals(rowsBefore, rowCount(docA, "1"), "rollback must restore full old data");
        assertEquals(hashBefore, jdbc.queryForObject("SELECT MIN(record_hash) FROM d43_table_row tr"
                + " JOIN d43_table_extract te ON tr.table_id=te.id"
                + " WHERE te.document_id=? AND te.version=?", String.class, docA, 1),
                "old rows must be bit-identical after rollback");
    }

    /** 场景7 版本隔离：仅重抽 v1，v2 不受影响。 */
    @Test
    void versionIsolation() {
        service.extractAndPersist(docA, "1", "txt",
                utf8(tableText("5 mm", 3)), null, tableText("5 mm", 3));
        service.extractAndPersist(docA, "2", "txt",
                utf8(tableText("6 mm", 2)), null, tableText("6 mm", 2));
        service.extractAndPersist(docA, "1", "txt",
                utf8(tableText("5 mm", 1)), null, tableText("5 mm", 1));
        assertEquals(1, rowCount(docA, "1"));
        assertEquals(2, rowCount(docA, "2"), "other version must be untouched");
    }

    /** 场景8 文档隔离：重抽 A，B 不受影响。 */
    @Test
    void documentIsolation() {
        service.extractAndPersist(docA, "1", "txt",
                utf8(tableText("4 mm", 3)), null, tableText("4 mm", 3));
        service.extractAndPersist(docB, "1", "txt",
                utf8(tableText("4 mm", 2)), null, tableText("4 mm", 2));
        service.extractAndPersist(docA, "1", "txt", utf8("纯文字"), null, "纯文字");
        assertEquals(0, rowCount(docA, "1"));
        assertEquals(2, rowCount(docB, "1"), "other document must be untouched");
        assertFalse(tableCount(docB, "1") == 0);
    }
}
