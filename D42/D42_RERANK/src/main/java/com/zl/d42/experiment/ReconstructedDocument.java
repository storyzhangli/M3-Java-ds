package com.zl.d42.experiment;

import com.zl.d42.experiment.ExperimentReasonCode;
import com.zl.d42.knowledge.KnowledgeChunk;
import com.zl.d42.knowledge.chunking.ChunkSourceDocument;
import com.zl.d42.knowledge.chunking.DocumentPageText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把扁平语料重建为可重新切分的源文档，并保留页码到逻辑片段 ID 的映射。
 *
 * <p>当前语料每个逻辑片段都有唯一且非空的物理页码，且 excerpt 为单段文本，
 * 因此 {@code page -> 逻辑 chunkId} 是一一映射；重新切分产出的新块通过其覆盖的
 * 页码即可回溯到一个或多个逻辑片段 ID。</p>
 *
 * @param source                 重建后的切分源（documentId 已带实验前缀）
 * @param originalDocumentId     原始文档 ID
 * @param pageToLogicalChunkId   物理页码到逻辑片段 ID 的一一映射
 */
public record ReconstructedDocument(ChunkSourceDocument source,
                                    String originalDocumentId,
                                    Map<Integer, String> pageToLogicalChunkId) {

    /**
     * 重建一个实验组所需的全部源文档。
     *
     * @param corpusChunks 扁平语料
     * @param group        实验组（决定 documentId 前缀与版本）
     * @return 按文档分组的重建结果（保持语料出现顺序）
     */
    public static List<ReconstructedDocument> rebuild(List<KnowledgeChunk> corpusChunks,
                                                       ExperimentGroup group) {
        Map<String, List<KnowledgeChunk>> byDocument = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : corpusChunks) {
            byDocument.computeIfAbsent(chunk.documentId(), key -> new ArrayList<>()).add(chunk);
        }

        List<ReconstructedDocument> documents = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgeChunk>> entry : byDocument.entrySet()) {
            String originalDocumentId = entry.getKey();
            List<KnowledgeChunk> chunks = entry.getValue().stream()
                    .sorted((a, b) -> Integer.compare(
                            requirePage(a, originalDocumentId),
                            requirePage(b, originalDocumentId)))
                    .toList();

            List<DocumentPageText> pages = new ArrayList<>();
            Map<Integer, String> mapping = new LinkedHashMap<>();
            String title = null;
            for (KnowledgeChunk chunk : chunks) {
                int page = requirePage(chunk, originalDocumentId);
                if (mapping.containsKey(page)) {
                    throw new IllegalStateException(
                            ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code()
                                    + ": 文档 " + originalDocumentId + " 存在重复页码 " + page);
                }
                pages.add(new DocumentPageText(page, chunk.excerpt()));
                mapping.put(page, chunk.chunkId());
                title = chunk.title();
            }

            String experimentDocumentId = group.documentIdPrefix() + originalDocumentId;
            ChunkSourceDocument source = new ChunkSourceDocument(
                    experimentDocumentId, title, group.experimentVersion(), pages);
            documents.add(new ReconstructedDocument(source, originalDocumentId, Map.copyOf(mapping)));
        }
        return List.copyOf(documents);
    }

    /**
     * 把新块覆盖的页码集合映射回逻辑片段 ID 集合。
     *
     * @throws IllegalStateException 若出现未登记页码（映射缺失）
     */
    public List<String> mapPagesToLogicalChunkIds(List<Integer> pageNumbers) {
        List<String> logicalIds = new ArrayList<>();
        for (Integer page : pageNumbers) {
            if (page == null) {
                continue;
            }
            String logicalId = pageToLogicalChunkId.get(page);
            if (logicalId == null) {
                throw new IllegalStateException(
                        ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code()
                                + ": 页码 " + page + " 未登记到逻辑片段");
            }
            if (!logicalIds.contains(logicalId)) {
                logicalIds.add(logicalId);
            }
        }
        return List.copyOf(logicalIds);
    }

    private static int requirePage(KnowledgeChunk chunk, String documentId) {
        if (chunk.page() == null) {
            throw new IllegalStateException(
                    ExperimentReasonCode.ID_MAPPING_AMBIGUOUS.code()
                            + ": 片段 " + chunk.chunkId() + " 缺少物理页码，无法稳定映射（文档 "
                            + documentId + "）");
        }
        return chunk.page();
    }
}
