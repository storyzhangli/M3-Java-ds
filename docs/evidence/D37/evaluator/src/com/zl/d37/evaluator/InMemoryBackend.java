package com.zl.d37.evaluator;

import com.zl.d37.evaluator.CorpusModel.CorpusChunk;
import com.zl.d37.evaluator.CorpusModel.CorpusDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * IN_MEMORY_LOCAL 内存降级检索后端。
 *
 * <p>以冻结语料按逻辑片段构建内存索引（与 D37 生产入库 CorpusIndexService 的
 * "每片段一块"口径一致），余弦相似度按真实向量计算；Top-K 排序规则固定：
 * 先按相似度降序，同分数以稳定 chunkId（逻辑片段 ID）作次级排序键，保证跨进程顺序一致。</p>
 *
 * <p>不调外部模型、不访问外网、不伪造数据库耗时；内存段耗时单独以
 * {@code inMemorySearchMs} 命名，禁止写入 {@code elapsedMillis} 的库内耗时口径。</p>
 */
public final class InMemoryBackend {

    private record IndexedChunk(CorpusChunk chunk,
                                String documentId,
                                String title,
                                String version,
                                int chunkIndex,
                                float[] vector) {
    }

    private final List<IndexedChunk> index = new ArrayList<>();
    private final LocalHashNgram embedder;
    private final Map<Integer, String> pageToLogicalChunkId;

    public InMemoryBackend(List<CorpusDocument> documents,
                           Map<Integer, String> pageToLogicalChunkId,
                           LocalHashNgram embedder) {
        this.embedder = embedder;
        this.pageToLogicalChunkId = pageToLogicalChunkId;
        for (CorpusDocument document : documents) {
            int chunkIndex = 0;
            for (CorpusChunk chunk : document.chunks()) {
                index.add(new IndexedChunk(
                        chunk,
                        document.documentId(),
                        document.title(),
                        "corpus-v1",
                        chunkIndex++,
                        embedder.embed(chunk.excerpt())));
            }
        }
    }

    public int indexedChunkCount() {
        return index.size();
    }

    /**
     * 执行内存余弦检索（返回的 searchResult 元组含命中列表与内存段耗时）。
     */
    public SearchResult search(String query, int topK, double similarityThreshold) {
        long start = System.nanoTime();
        float[] queryVector = embedder.embed(query);
        List<Ranked> ranked = new ArrayList<>(index.size());
        for (IndexedChunk chunk : index) {
            double score = LocalHashNgram.cosine(queryVector, chunk.vector());
            ranked.add(new Ranked(chunk, score));
        }
        // 稳定排序：相似度降序，同分按逻辑片段 ID 升序（跨进程一致）
        ranked.sort(Comparator
                .comparingDouble(Ranked::score).reversed()
                .thenComparing(r -> r.chunk().chunk().chunkId()));
        List<Hit> hits = new ArrayList<>();
        int rank = 1;
        for (Ranked r : ranked) {
            if (r.score() < similarityThreshold) {
                break;
            }
            if (hits.size() >= topK) {
                break;
            }
            IndexedChunk c = r.chunk();
            List<Integer> pageNumbers = c.chunk().page() == null
                    ? List.of() : List.of(c.chunk().page());
            List<String> logicalIds = new ArrayList<>();
            if (c.chunk().page() != null) {
                String logical = pageToLogicalChunkId.get(c.chunk().page());
                if (logical != null) {
                    logicalIds.add(logical);
                }
            }
            hits.add(new Hit(
                    rank++,
                    c.chunk().chunkId(),
                    c.documentId(),
                    c.version(),
                    c.chunkIndex(),
                    c.title(),
                    pageNumbers,
                    c.chunk().excerpt(),
                    r.score(),
                    1.0 - r.score(),
                    List.copyOf(logicalIds)));
        }
        long inMemorySearchMs = (System.nanoTime() - start) / 1_000_000L;
        return new SearchResult(List.copyOf(hits), inMemorySearchMs);
    }

    private record Ranked(IndexedChunk chunk, double score) {
    }

    public record SearchResult(List<Hit> hits, long inMemorySearchMs) {
    }
}
