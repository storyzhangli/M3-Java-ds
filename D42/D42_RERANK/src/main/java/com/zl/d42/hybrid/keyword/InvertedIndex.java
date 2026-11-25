package com.zl.d42.hybrid.keyword;

import com.zl.d42.hybrid.keyword.ChunkRow;
import com.zl.d42.hybrid.keyword.KeywordTokenizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 进程内轻量倒排索引（keywordRoute=IN_PROCESS_INVERTED，KP1 裁决，零 DDL、零写库）。
 *
 * <p>构建输入必须是 PG {@code vector_store} <b>全表行集</b>（D4 裁决：含 d36exp/d34 残留行），
 * 与向量路可见性严格一致；只建语料 8 片段会人为过滤噪声=引入第二变量。</p>
 *
 * <p>确定性构建（P1.2 / §3.3）：</p>
 * <ul>
 *   <li>文档按 chunkId 升序处理；</li>
 *   <li>term 字典序（TreeMap）；posting 按 chunkId 升序（TreeMap）；</li>
 *   <li>avgDocLen 按 chunkId 升序求和，浮点累加顺序固定；</li>
 *   <li>提供 {@link #fingerprint()}（ postings 序列化的 SHA-256），两次构建指纹一致
 *       即结构级字节一致（C03 判据）。</li>
 * </ul>
 *
 * <p>构建后只读；重建通过整体替换引用完成，本类实例不可变。</p>
 */
public final class InvertedIndex {

    /** term → (chunkId → tf)。两层均 TreeMap，字典序/chunkId 升序。 */
    private final TreeMap<String, TreeMap<String, Integer>> postings;

    private final Map<String, ChunkRow> documents;

    /** 文档长度 = 特征计数总和（BM25 len 语义）。 */
    private final Map<String, Integer> docLengths;

    private final double avgDocLen;

    private final IndexStats stats;

    private InvertedIndex(TreeMap<String, TreeMap<String, Integer>> postings,
                          Map<String, ChunkRow> documents,
                          Map<String, Integer> docLengths,
                          double avgDocLen,
                          long buildMillis,
                          long heapDeltaBytes) {
        this.postings = postings;
        this.documents = Map.copyOf(documents);
        this.docLengths = Map.copyOf(docLengths);
        this.avgDocLen = avgDocLen;
        long termCount = postings.size();
        long postingCount = 0;
        long indexUtf8Bytes = 0;
        long contentUtf8Bytes = 0;
        for (Map.Entry<String, TreeMap<String, Integer>> entry : postings.entrySet()) {
            indexUtf8Bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            postingCount += entry.getValue().size();
            for (String chunkId : entry.getValue().keySet()) {
                indexUtf8Bytes += chunkId.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        for (ChunkRow row : documents.values()) {
            contentUtf8Bytes += row.content() == null ? 0
                    : row.content().getBytes(StandardCharsets.UTF_8).length;
        }
        this.stats = new IndexStats(documents.size(), termCount, postingCount,
                contentUtf8Bytes, indexUtf8Bytes, buildMillis, heapDeltaBytes);
    }

    /**
     * 确定性构建入口。rows 内部顺序不影响结果（构建前按 chunkId 排序）。
     */
    public static InvertedIndex build(List<ChunkRow> rows, KeywordTokenizer tokenizer) {
        long start = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();

        List<ChunkRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(ChunkRow::chunkId));

        TreeMap<String, TreeMap<String, Integer>> postings = new TreeMap<>();
        Map<String, ChunkRow> documents = new TreeMap<>();
        Map<String, Integer> docLengths = new TreeMap<>();
        long totalLen = 0;
        for (ChunkRow row : sorted) {
            if (documents.containsKey(row.chunkId())) {
                throw new IllegalStateException("倒排构建发现重复 chunkId: " + row.chunkId());
            }
            documents.put(row.chunkId(), row);
            Map<String, Integer> tf = tokenizer.tokenCounts(row.content());
            int len = 0;
            for (Map.Entry<String, Integer> entry : tf.entrySet()) {
                postings.computeIfAbsent(entry.getKey(), k -> new TreeMap<>())
                        .merge(row.chunkId(), entry.getValue(), Integer::sum);
                len += entry.getValue();
            }
            docLengths.put(row.chunkId(), len);
            totalLen += len;
        }
        // avgDocLen 固定按 chunkId 升序（TreeMap 迭代序）求平均，浮点顺序确定
        double sum = 0.0;
        for (String chunkId : docLengths.keySet()) {
            sum += docLengths.get(chunkId);
        }
        double avg = docLengths.isEmpty() ? 0.0 : sum / docLengths.size();
        long buildMillis = (System.nanoTime() - start) / 1_000_000L;
        long heapDelta = (runtime.totalMemory() - runtime.freeMemory()) - heapBefore;
        return new InvertedIndex(postings, documents, docLengths, avg, buildMillis, heapDelta);
    }

    /** term 的 posting 表（chunkId → tf）；不存在返回 null。 */
    public TreeMap<String, Integer> postings(String term) {
        return postings.get(term);
    }

    public ChunkRow document(String chunkId) {
        return documents.get(chunkId);
    }

    public int docLength(String chunkId) {
        return docLengths.getOrDefault(chunkId, 0);
    }

    public double avgDocLen() {
        return avgDocLen;
    }

    public int docCount() {
        return documents.size();
    }

    public Map<String, ChunkRow> documentsView() {
        return documents;
    }

    public IndexStats stats() {
        return stats;
    }

    /**
     * 结构指纹：term/chunkId/tf 按迭代序拼接后 SHA-256。
     * 两次构建（同一行集）指纹一致 ⇔ 索引结构确定（C03）。
     */
    public String fingerprint() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        for (Map.Entry<String, TreeMap<String, Integer>> entry : postings.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (Map.Entry<String, Integer> posting : entry.getValue().entrySet()) {
                digest.update(posting.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(Integer.toString(posting.getValue()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update((byte) 1);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 索引统计（成本口径 H7：term 数/posting 数/UTF-8 字节精确；堆增量为粗测口径，GC 噪声注明）。
     */
    public record IndexStats(int docCount,
                             long termCount,
                             long postingCount,
                             long contentUtf8Bytes,
                             long indexUtf8Bytes,
                             long buildMillis,
                             long heapDeltaBytes) {
    }
}
