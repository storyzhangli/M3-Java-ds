package com.zl.d38.hybrideval;

import java.util.List;

/**
 * 冻结语料模型（复制自 D37 评测器 CorpusModel，零改动，仅换包名）。
 */
public final class CorpusModel {

    private CorpusModel() {
    }

    /** 语料文档：3 文档 8 片段。 */
    public record CorpusDocument(String documentId,
                                 String title,
                                 String sourceType,
                                 String url,
                                 List<CorpusChunk> chunks) {
    }

    /** 语料片段（检索与引用的最小真实单元）。 */
    public record CorpusChunk(String chunkId,
                              String section,
                              Integer page,
                              String excerpt) {
    }
}
