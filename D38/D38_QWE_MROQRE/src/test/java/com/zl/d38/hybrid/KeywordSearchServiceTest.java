package com.zl.d38.hybrid;

import com.zl.d38.common.ResultCode;
import com.zl.d38.hybrid.config.HybridProperties;
import com.zl.d38.hybrid.keyword.ChunkRow;
import com.zl.d38.hybrid.keyword.InvertedIndex;
import com.zl.d38.hybrid.keyword.KeywordMatchView;
import com.zl.d38.hybrid.keyword.KeywordSearchService;
import com.zl.d38.hybrid.keyword.KeywordTokenizer;
import com.zl.d38.knowledge.retrieval.RetrievalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 关键词检索手算夹具（P1.3 / C07 组件级）：公式逐值核对、同分 chunkId tie-break、
 * 过滤口径、EMPTY ≠ FAILED、参数校验，以及倒排确定性构建指纹（C03 组件级支撑）。
 *
 * <p>夹具用 featureSubset=w（隔离 n-gram 复杂度做纯公式核对；主实验冻结 w+b+t 由
 * KeywordTokenizerTest 与端到端评测覆盖）。文档集：</p>
 * <ul>
 *   <li>d1 "cool pump"（DOC-A, len=2）</li>
 *   <li>d2 "cool cool pump"（DOC-A, len=3）</li>
 *   <li>a4 "cool pump"（DOC-A, len=2，与 d1 同分异 chunkId → tie-break 素材）</li>
 *   <li>d3 "valve"（DOC-B, len=1）</li>
 * </ul>
 */
class KeywordSearchServiceTest {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static ChunkRow row(String chunkId, String documentId, String content) {
        return new ChunkRow(chunkId, documentId, "corpus-v1", 0, "标题", List.of(), null,
                "FIXED_LENGTH", content, "ACTIVE", null, null);
    }

    private static List<ChunkRow> fixtureRows() {
        return new ArrayList<>(List.of(
                row("d1", "DOC-A", "cool pump"),
                row("d2", "DOC-A", "cool cool pump"),
                row("a4", "DOC-A", "cool pump"),
                row("d3", "DOC-B", "valve")));
    }

    private static KeywordSearchService service() {
        HybridProperties properties = new HybridProperties();
        properties.setFeatureSubset("w");
        KeywordTokenizer tokenizer = new KeywordTokenizer("w");
        return KeywordSearchService.forTesting(
                InvertedIndex.build(fixtureRows(), tokenizer), tokenizer, properties);
    }

    /** 手算基准：IDF=ln(1+(N−df+0.5)/(df+0.5))；tf 项=tf·(k1+1)/(tf+k1·(1−b+b·len/avgLen))。 */
    private static double idf(int n, int df) {
        return Math.log(1.0 + ((double) n - df + 0.5) / (df + 0.5));
    }

    private static double tfPart(double tf, double len, double avgLen) {
        return tf * (K1 + 1.0) / (tf + K1 * (1.0 - B + B * (len / avgLen)));
    }

    @Test
    @DisplayName("BM25 单 term：pump 对手算值逐位一致，短文档（len=2）优于长文档（len=3）")
    void bm25HandComputedSingleTerm() {
        List<KeywordMatchView> hits = service().search("pump", 10, null, null, null);
        double avgLen = (2 + 3 + 2 + 1) / 4.0;
        double expectedD1 = idf(4, 3) * tfPart(1, 2, avgLen);
        double expectedD2 = idf(4, 3) * tfPart(1, 3, avgLen);
        // pump 出现在 d1/d2/a4：df=3；a4/d1 同分按 chunkId 升序在前
        assertEquals(3, hits.size());
        assertEquals("a4", hits.get(0).chunkId());
        assertEquals(expectedD1, hits.get(0).score(), 1e-12);
        assertEquals("d1", hits.get(1).chunkId());
        assertEquals(expectedD1, hits.get(1).score(), 1e-12);
        assertEquals("d2", hits.get(2).chunkId());
        assertEquals(expectedD2, hits.get(2).score(), 1e-12);
        assertEquals(List.of("w:pump"), hits.get(0).matchedTerms());
    }

    @Test
    @DisplayName("同分 tie-break：d1 与 a4 分数一致 → chunkId 升序（a4 在前，d2 分最高居首）")
    void tieBreakByChunkIdAscending() {
        List<KeywordMatchView> hits = service().search("cool pump", 10, null, null, null);
        assertEquals("d2", hits.get(0).chunkId());
        assertEquals("a4", hits.get(1).chunkId(), "同分命中按 chunkId 升序");
        assertEquals("d1", hits.get(2).chunkId());
    }

    @Test
    @DisplayName("词频饱和：高 tf 文档在双 term 查询下仍居首（tf·(k1+1)/(tf+k1·…) 递增但次线性）")
    void bm25TermFrequencySaturation() {
        List<KeywordMatchView> hits = service().search("cool pump", 10, null, null, null);
        assertEquals("d2", hits.get(0).chunkId());
        double avgLen = 2.0;
        double expectedD2 = idf(4, 3) * tfPart(2, 3, avgLen) + idf(4, 3) * tfPart(1, 3, avgLen);
        assertEquals(expectedD2, hits.get(0).score(), 1e-12);
    }

    @Test
    @DisplayName("过滤口径：documentId=DOC-B 只剩 valve 文档；同分不跨过滤集")
    void metadataFilterParity() {
        List<KeywordMatchView> hits = service().search("pump valve", 10, "DOC-B", null, null);
        assertEquals(1, hits.size());
        assertEquals("d3", hits.get(0).chunkId());
        // 过滤值白名单校验与向量路同口径
        RetrievalException ex = assertThrows(RetrievalException.class,
                () -> service().search("pump", 10, "DOC B; DROP", null, null));
        assertEquals("FILTER_VALUE_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("EMPTY ≠ FAILED：零命中返回空列表；空查询才拒绝")
    void emptyIsNotFailed() {
        assertTrue(service().search("nonexistentterm", 10, null, null, null).isEmpty());
        RetrievalException blank = assertThrows(RetrievalException.class,
                () -> service().search("   ", 10, null, null, null));
        assertEquals("QUERY_BLANK", blank.getErrorCode());
        assertEquals(ResultCode.PARAM_INVALID.getCode(), blank.getResultCode().getCode());
    }

    @Test
    @DisplayName("topN 截断生效：topN=1 仅返回头名")
    void topNTruncation() {
        assertEquals(1, service().search("cool", 1, null, null, null).size());
    }

    @Test
    @DisplayName("确定性构建（C03）：乱序输入两次构建指纹一致，行数/term 数一致")
    void deterministicBuildFingerprint() {
        KeywordTokenizer tokenizer = new KeywordTokenizer("w+b+t");
        InvertedIndex first = InvertedIndex.build(fixtureRows(), tokenizer);
        List<ChunkRow> shuffled = new ArrayList<>(fixtureRows());
        Collections.reverse(shuffled);
        InvertedIndex second = InvertedIndex.build(shuffled, tokenizer);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.docCount(), second.docCount());
        assertEquals(first.stats().termCount(), second.stats().termCount());
        assertTrue(first.stats().indexUtf8Bytes() > 0);
    }
}
