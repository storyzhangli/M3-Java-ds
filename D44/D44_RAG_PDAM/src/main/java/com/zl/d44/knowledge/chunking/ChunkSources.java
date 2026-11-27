package com.zl.d44.knowledge.chunking;

import com.zl.d44.knowledge.chunking.ChunkSource;

import java.util.List;

/**
 * 切分来源（ChunkSource）聚合工具：段落索引在切分阶段真实存在，但向量 metadata 需单值表达。
 */
public final class ChunkSources {

    private ChunkSources() {
    }

    /**
     * 取第一个非空段落索引（chunk 可能跨多个段落，取起始段落作为引用定位）。
     * 无段落来源返回 null，如实缺失不补造。
     */
    public static Integer firstParagraphIndex(List<ChunkSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        for (ChunkSource source : sources) {
            if (source != null && source.paragraphIndex() != null) {
                return source.paragraphIndex();
            }
        }
        return null;
    }
}
