package com.zl.d44.hybrid.keyword;

import com.zl.d44.access.AccessPolicyService;
import com.zl.d44.common.ResultCode;
import com.zl.d44.hybrid.config.HybridProperties;
import com.zl.d44.hybrid.keyword.ChunkRow;
import com.zl.d44.hybrid.keyword.HybridIndexLoader;
import com.zl.d44.hybrid.keyword.InvertedIndex;
import com.zl.d44.hybrid.keyword.KeywordMatchView;
import com.zl.d44.hybrid.keyword.KeywordTokenizer;
import com.zl.d44.knowledge.retrieval.RetrievalException;
import com.zl.d44.knowledge.vectorstore.PgVectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BM25 关键词检索服务（P1.3）。
 *
 * <p>评分公式（冻结）：{@code score(q,d)=Σ_t IDF(t)·tf·(k1+1)/(tf+k1·(1−b+b·len/avgLen))}，
 * {@code IDF(t)=ln(1+(N−df+0.5)/(df+0.5))}。求和遍历<b>去重后的查询 term</b>（TreeMap 字典序，
 * 固定浮点累加顺序），不引入 qtf 饱和项——该口径写入技术文档，禁止实现外私自"优化"。</p>
 *
 * <p>排序：分数降序 → <b>同分 chunkId 升序</b>（确定性 tie-break；d36exp 同文本副本天然并列，
 * 排序规则是确定性保障而非语义修复，§3.5）。</p>
 *
 * <p>语义边界：空查询拒绝（QUERY_BLANK）；零命中返回空列表（EMPTY ≠ FAILED）；
 * 索引不可用/加载失败抛异常（FAILED）。过滤口径与向量路一致：documentId/version/strategy
 * 精确匹配 + 同白名单校验（{@code [A-Za-z0-9._:-]+}，≤128）。</p>
 */
@Service
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    /** 过滤值白名单（与 RetrievalFilterBuilder 同口径，独立持有避免触包外私有实现）。 */
    private static final int MAX_FILTER_VALUE_LENGTH = 128;
    private static final String SAFE_VALUE_PATTERN = "[A-Za-z0-9._:-]+";

    private final AtomicReference<InvertedIndex> indexRef = new AtomicReference<>();

    private final KeywordTokenizer tokenizer;

    private final HybridProperties hybridProperties;

    private final PgVectorStoreProperties pgProperties;

    /**
     * Spring 装配入口：立即只读加载 PG 全表并构建（fail-fast，无内存 fallback，§3.7）。
     *
     * <p>必须显式 {@code @Autowired}：本类另有私有 4 参构造（测试工厂用），
     * 多构造候选时 Spring 不会自动择 public 构造，会回退找默认构造而启动失败
     * （实例 B 真实启动 L2 门禁暴露）。</p>
     */
    @Autowired
    public KeywordSearchService(KeywordTokenizer tokenizer,
                                HybridProperties hybridProperties,
                                PgVectorStoreProperties pgProperties) {
        this(tokenizer, hybridProperties, pgProperties, true);
    }

    private KeywordSearchService(KeywordTokenizer tokenizer,
                                 HybridProperties hybridProperties,
                                 PgVectorStoreProperties pgProperties,
                                 boolean eagerLoad) {
        this.tokenizer = tokenizer;
        this.hybridProperties = hybridProperties;
        this.pgProperties = pgProperties;
        if (eagerLoad) {
            this.indexRef.set(buildFromDatabase());
        }
    }

    private InvertedIndex buildFromDatabase() {
        List<ChunkRow> rows = HybridIndexLoader.loadAll(pgProperties);
        InvertedIndex index = InvertedIndex.build(rows, tokenizer);
        log.info("混合检索倒排装配完成: docs={}, terms={}, postings={}, fingerprint={}",
                index.docCount(), index.stats().termCount(), index.stats().postingCount(),
                index.fingerprint());
        return index;
    }

    /** 测试专用：注入预构建索引，不触库。 */
    public static KeywordSearchService forTesting(InvertedIndex initialIndex,
                                                  KeywordTokenizer tokenizer,
                                                  HybridProperties hybridProperties) {
        KeywordSearchService service = new KeywordSearchService(tokenizer, hybridProperties, null, false);
        service.indexRef.set(initialIndex);
        return service;
    }

    /** 显式重建（P1.2：仅重建内存索引，零写入）。返回新索引统计。 */
    public InvertedIndex.IndexStats rebuild() {
        List<ChunkRow> rows = HybridIndexLoader.loadAll(pgProperties);
        InvertedIndex fresh = InvertedIndex.build(rows, tokenizer);
        this.indexRef.set(fresh);
        log.info("倒排重建完成: docs={}, fingerprint={}", fresh.docCount(), fresh.fingerprint());
        return fresh.stats();
    }

    public InvertedIndex currentIndex() {
        return indexRef.get();
    }

    /**
     * BM25 Top-N 检索。
     *
     * @param topN       候选截断（=candidateTopN，冻结 10）
     * @param documentId/version/strategy 可选过滤，null/空白=不过滤
     */
    public List<KeywordMatchView> search(String query, int topN,
                                         String documentId, String version, String strategy) {
        return search(query, topN, documentId, version, strategy, null);
    }

    /**
     * 权限重载：{@code scope} 非空时先按权限三键过滤、再进入 BM25 评分（Top-N 截断
     * 之前，过滤先于截断与向量路口径一致）；{@code scope=null}=关闭态原语义（零差异 H1）。
     */
    public List<KeywordMatchView> search(String query, int topN,
                                         String documentId, String version, String strategy,
                                         AccessPolicyService.PermissionScope scope) {
        if (query == null || query.isBlank()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "查询内容不能为空", "QUERY_BLANK");
        }
        String docFilter = requireSafe(documentId, "documentId");
        String versionFilter = requireSafe(version, "version");
        String strategyFilter = requireSafe(strategy, "strategy");

        InvertedIndex index = indexRef.get();
        Map<String, Integer> queryTerms = tokenizer.tokenCounts(query);
        if (queryTerms.isEmpty()) {
            // 规范化后无任何可检索特征（如纯符号）：EMPTY，非 FAILED
            return List.of();
        }
        int n = index.docCount();
        double avgLen = index.avgDocLen();
        double k1 = hybridProperties.getBm25K1();
        double b = hybridProperties.getBm25B();

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> matched = new TreeMap<>();
        for (Map.Entry<String, Integer> queryEntry : queryTerms.entrySet()) {
            String term = queryEntry.getKey();
            TreeMap<String, Integer> posting = index.postings(term);
            if (posting == null || posting.isEmpty()) {
                continue;
            }
            int df = posting.size();
            double idf = Math.log(1.0 + ((double) n - df + 0.5) / (df + 0.5));
            for (Map.Entry<String, Integer> entry : posting.entrySet()) {
                String chunkId = entry.getKey();
                ChunkRow row = index.document(chunkId);
                if (!matchesFilter(row, docFilter, versionFilter, strategyFilter)) {
                    continue;
                }
                // 权限过滤先于评分与截断（缺失键行被 scope.allows 天然拒绝）
                if (scope != null && !scope.allows(row.tenantId(), row.deptId(), row.deviceId())) {
                    continue;
                }
                double tf = entry.getValue();
                double len = index.docLength(chunkId);
                double norm = avgLen <= 0.0 ? 1.0 : len / avgLen;
                double contribution = idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * norm));
                scores.merge(chunkId, contribution, Double::sum);
                matched.computeIfAbsent(chunkId, key -> new ArrayList<>()).add(term);
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Double>, Double>comparing(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        List<KeywordMatchView> hits = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> entry : ranked) {
            if (rank > topN) {
                break;
            }
            ChunkRow row = index.document(entry.getKey());
            hits.add(new KeywordMatchView(rank++, row.chunkId(), row.documentId(),
                    row.version(), row.chunkIndex(), row.title(), row.pageNumbers(),
                    row.paragraphIndex(), row.strategy(), row.content(),
                    entry.getValue(), index.docLength(row.chunkId()),
                    matched.getOrDefault(row.chunkId(), List.of()),
                    row.lifecycleStatus(), row.effectiveAt(), row.expiresAt()));
        }
        return List.copyOf(hits);
    }

    private boolean matchesFilter(ChunkRow row, String documentId, String version, String strategy) {
        if (documentId != null && !documentId.equals(row.documentId())) {
            return false;
        }
        if (version != null && !version.equals(row.version())) {
            return false;
        }
        return strategy == null || strategy.equals(row.strategy());
    }

    static String requireSafe(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_FILTER_VALUE_LENGTH || !trimmed.matches(SAFE_VALUE_PATTERN)) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "过滤字段 " + fieldName + " 的值不合法：仅允许字母、数字、点、下划线、冒号、连字符，"
                            + "长度不超过 " + MAX_FILTER_VALUE_LENGTH,
                    "FILTER_VALUE_INVALID");
        }
        return trimmed;
    }
}
