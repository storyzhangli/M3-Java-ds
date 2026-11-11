package com.zl.d28.knowledge.chunking;

import com.zl.d28.common.ResultCode;
import com.zl.d28.knowledge.document.KnowledgeDocument;
import com.zl.d28.knowledge.document.KnowledgeDocumentException;
import com.zl.d28.knowledge.document.KnowledgeDocumentPage;
import com.zl.d28.knowledge.document.KnowledgeDocumentStatus;
import com.zl.d28.knowledge.document.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Adapts persisted D28 page extraction to the independent chunker.
 */
@Service
public class KnowledgeDocumentChunkingService {

    private final KnowledgeDocumentRepository repository;

    private final DocumentChunker chunker;

    public KnowledgeDocumentChunkingService(KnowledgeDocumentRepository repository,
                                            DocumentChunker chunker) {
        this.repository = repository;
        this.chunker = chunker;
    }

    public ChunkingResult preview(String documentId, ChunkStrategy strategy) {
        return chunker.chunk(source(documentId), strategy);
    }

    public ChunkingComparison compare(String documentId) {
        return chunker.compare(source(documentId));
    }

    private ChunkSourceDocument source(String documentId) {
        KnowledgeDocument document = repository.findByDocumentId(documentId)
                .orElseThrow(() -> new KnowledgeDocumentException(
                        ResultCode.DOCUMENT_NOT_FOUND,
                        "知识文档不存在: " + documentId));
        if (document.getStatus() != KnowledgeDocumentStatus.COMPLETED) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档尚未完成解析，不能执行切分");
        }
        List<KnowledgeDocumentPage> pages = repository.findExtractedPages(documentId);
        if (pages.isEmpty()) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档没有页面级文本，不能执行 PDF Chunk 预览");
        }
        List<DocumentPageText> pageTexts = pages.stream()
                .map(page -> new DocumentPageText(page.pageNumber(), page.content()))
                .toList();
        return new ChunkSourceDocument(
                document.getDocumentId(),
                document.getTitle(),
                document.getVersion(),
                pageTexts);
    }
}
