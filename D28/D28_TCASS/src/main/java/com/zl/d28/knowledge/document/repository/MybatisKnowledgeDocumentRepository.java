package com.zl.d28.knowledge.document.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zl.d28.common.ResultCode;
import com.zl.d28.knowledge.document.ExtractedPage;
import com.zl.d28.knowledge.document.KnowledgeDocument;
import com.zl.d28.knowledge.document.KnowledgeDocumentException;
import com.zl.d28.knowledge.document.KnowledgeDocumentPage;
import com.zl.d28.knowledge.document.KnowledgeDocumentStatus;
import com.zl.d28.knowledge.document.mapper.KnowledgeDocumentContentMapper;
import com.zl.d28.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.zl.d28.knowledge.document.mapper.KnowledgeDocumentPageMapper;
import com.zl.d28.knowledge.document.po.KnowledgeDocumentContentPO;
import com.zl.d28.knowledge.document.po.KnowledgeDocumentPagePO;
import com.zl.d28.knowledge.document.po.KnowledgeDocumentPO;
import com.zl.d28.knowledge.document.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper documentMapper;

    private final KnowledgeDocumentContentMapper contentMapper;

    private final KnowledgeDocumentPageMapper pageMapper;

    public MybatisKnowledgeDocumentRepository(KnowledgeDocumentMapper documentMapper,
                                              KnowledgeDocumentContentMapper contentMapper,
                                              KnowledgeDocumentPageMapper pageMapper) {
        this.documentMapper = documentMapper;
        this.contentMapper = contentMapper;
        this.pageMapper = pageMapper;
    }

    @Override
    @Transactional
    public KnowledgeDocument register(KnowledgeDocument document) {
        KnowledgeDocumentPO po = toPO(document);
        documentMapper.insert(po);
        document.setId(po.getId());
        return document;
    }

    @Override
    public Optional<KnowledgeDocument> findByDocumentId(String documentId) {
        return Optional.ofNullable(documentMapper.selectOne(
                        new LambdaQueryWrapper<KnowledgeDocumentPO>()
                                .eq(KnowledgeDocumentPO::getDocumentId, documentId)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public Optional<KnowledgeDocument> findByContentHash(String contentHash) {
        return Optional.ofNullable(documentMapper.selectOne(
                        new LambdaQueryWrapper<KnowledgeDocumentPO>()
                                .eq(KnowledgeDocumentPO::getContentHash, contentHash)
                                .last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public void claimForProcessing(String documentId, String fileName, String contentType,
                                   long fileSize, String contentHash, byte[] content,
                                   LocalDateTime updatedAt) {
        int updated = documentMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeDocumentPO>()
                        .eq(KnowledgeDocumentPO::getDocumentId, documentId)
                        .in(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.PENDING.name(),
                                KnowledgeDocumentStatus.FAILED.name())
                        .set(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.PROCESSING.name())
                        .set(KnowledgeDocumentPO::getFileName, fileName)
                        .set(KnowledgeDocumentPO::getContentType, contentType)
                        .set(KnowledgeDocumentPO::getFileSize, fileSize)
                        .set(KnowledgeDocumentPO::getContentHash, contentHash)
                        .set(KnowledgeDocumentPO::getErrorCode, "")
                        .set(KnowledgeDocumentPO::getErrorMessage, "")
                        .set(KnowledgeDocumentPO::getUpdatedAt, updatedAt));
        if (updated != 1) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档当前状态不允许开始导入");
        }

        contentMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentContentPO>()
                .eq(KnowledgeDocumentContentPO::getDocumentId, documentId));
        pageMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentPagePO>()
                .eq(KnowledgeDocumentPagePO::getDocumentId, documentId));
        KnowledgeDocumentContentPO contentPO = new KnowledgeDocumentContentPO();
        contentPO.setDocumentId(documentId);
        contentPO.setContent(content);
        contentPO.setParsedText(null);
        contentPO.setUpdatedAt(updatedAt);
        contentMapper.insert(contentPO);
    }

    @Override
    @Transactional
    public void complete(String documentId, String contentType, String parsedText,
                         List<ExtractedPage> pages, String parserName, String parserVersion,
                         LocalDateTime updatedAt) {
        int updated = documentMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeDocumentPO>()
                        .eq(KnowledgeDocumentPO::getDocumentId, documentId)
                        .eq(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.PROCESSING.name())
                        .set(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.COMPLETED.name())
                        .set(KnowledgeDocumentPO::getContentType, contentType)
                        .set(KnowledgeDocumentPO::getErrorCode, "")
                        .set(KnowledgeDocumentPO::getErrorMessage, "")
                        .set(KnowledgeDocumentPO::getUpdatedAt, updatedAt));
        if (updated != 1) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档导入完成状态更新冲突");
        }
        contentMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeDocumentContentPO>()
                        .eq(KnowledgeDocumentContentPO::getDocumentId, documentId)
                        .set(KnowledgeDocumentContentPO::getParsedText, parsedText)
                        .set(KnowledgeDocumentContentPO::getUpdatedAt, updatedAt));
        for (ExtractedPage page : pages) {
            KnowledgeDocumentPagePO pagePO = new KnowledgeDocumentPagePO();
            pagePO.setDocumentId(documentId);
            pagePO.setPageNumber(page.pageNumber());
            pagePO.setContent(page.content());
            pagePO.setCharacterCount(page.characterCount());
            pagePO.setBlankPage(page.blankPage());
            pagePO.setParserName(parserName);
            pagePO.setParserVersion(parserVersion);
            pagePO.setUpdatedAt(updatedAt);
            pageMapper.insert(pagePO);
        }
    }

    @Override
    public List<KnowledgeDocumentPage> findExtractedPages(String documentId) {
        return pageMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeDocumentPagePO>()
                                .eq(KnowledgeDocumentPagePO::getDocumentId, documentId)
                                .orderByAsc(KnowledgeDocumentPagePO::getPageNumber))
                .stream()
                .map(this::toPageDomain)
                .toList();
    }

    @Override
    public int countExtractedPages(String documentId) {
        return Math.toIntExact(pageMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocumentPagePO>()
                        .eq(KnowledgeDocumentPagePO::getDocumentId, documentId)));
    }

    @Override
    @Transactional
    public void fail(String documentId, String errorCode, String errorMessage,
                     LocalDateTime updatedAt) {
        documentMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeDocumentPO>()
                        .eq(KnowledgeDocumentPO::getDocumentId, documentId)
                        .in(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.PENDING.name(),
                                KnowledgeDocumentStatus.PROCESSING.name(),
                                KnowledgeDocumentStatus.FAILED.name())
                        .set(KnowledgeDocumentPO::getStatus,
                                KnowledgeDocumentStatus.FAILED.name())
                        .set(KnowledgeDocumentPO::getErrorCode, safe(errorCode, 64))
                        .set(KnowledgeDocumentPO::getErrorMessage, safe(errorMessage, 500))
                        .set(KnowledgeDocumentPO::getUpdatedAt, updatedAt));
    }

    private KnowledgeDocumentPO toPO(KnowledgeDocument document) {
        KnowledgeDocumentPO po = new KnowledgeDocumentPO();
        po.setId(document.getId());
        po.setDocumentId(document.getDocumentId());
        po.setSourceSystem(document.getSourceSystem());
        po.setTitle(document.getTitle());
        po.setVersion(document.getVersion());
        po.setStatus(document.getStatus().name());
        po.setFileName(document.getFileName());
        po.setContentType(document.getContentType());
        po.setFileSize(document.getFileSize());
        po.setContentHash(document.getContentHash());
        po.setErrorCode(safe(document.getErrorCode(), 64));
        po.setErrorMessage(safe(document.getErrorMessage(), 500));
        po.setCreatedAt(document.getCreatedAt());
        po.setUpdatedAt(document.getUpdatedAt());
        return po;
    }

    private KnowledgeDocument toDomain(KnowledgeDocumentPO po) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(po.getId());
        document.setDocumentId(po.getDocumentId());
        document.setSourceSystem(po.getSourceSystem());
        document.setTitle(po.getTitle());
        document.setVersion(po.getVersion());
        document.setStatus(KnowledgeDocumentStatus.valueOf(po.getStatus()));
        document.setFileName(po.getFileName());
        document.setContentType(po.getContentType());
        document.setFileSize(po.getFileSize());
        document.setContentHash(po.getContentHash());
        document.setErrorCode(po.getErrorCode());
        document.setErrorMessage(po.getErrorMessage());
        document.setCreatedAt(po.getCreatedAt());
        document.setUpdatedAt(po.getUpdatedAt());
        return document;
    }

    private KnowledgeDocumentPage toPageDomain(KnowledgeDocumentPagePO po) {
        return new KnowledgeDocumentPage(
                po.getDocumentId(),
                po.getPageNumber(),
                po.getContent(),
                po.getCharacterCount(),
                Boolean.TRUE.equals(po.getBlankPage()),
                po.getParserName(),
                po.getParserVersion(),
                po.getUpdatedAt());
    }

    private String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
