package com.zl.d31.knowledge.document;

import com.zl.d31.common.ResultCode;
import com.zl.d31.knowledge.document.dto.KnowledgeDocumentExtractionView;
import com.zl.d31.knowledge.document.dto.KnowledgeDocumentPageView;
import com.zl.d31.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d31.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d31.knowledge.document.repository.KnowledgeDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 知识文档登记、导入、状态和去重业务服务。
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    private final KnowledgeDocumentRepository repository;

    private final KnowledgeDocumentParser parser;

    public KnowledgeDocumentService(KnowledgeDocumentRepository repository,
                                    KnowledgeDocumentParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    public KnowledgeDocumentView register(KnowledgeDocumentRegistrationRequest request) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocumentId(UUID.randomUUID().toString().replace("-", ""));
        document.setSourceSystem(request.getSource().strip());
        document.setTitle(request.getTitle().strip());
        document.setVersion(request.getVersion().strip());
        document.setStatus(KnowledgeDocumentStatus.PENDING);
        document.setErrorCode("");
        document.setErrorMessage("");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        repository.register(document);
        log.info("知识文档已登记，documentId={}，version={}", document.getDocumentId(), document.getVersion());
        return view(document, null, 0, false);
    }

    public KnowledgeDocumentView importFile(String documentId, MultipartFile file) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档正在处理中，不能重复导入");
        }

        KnowledgeDocumentParser.InspectedFile inspected;
        try {
            inspected = parser.inspect(file);
        } catch (RuntimeException ex) {
            failSafely(documentId, ex);
            throw ex;
        }

        if (document.getStatus() == KnowledgeDocumentStatus.COMPLETED) {
            if (inspected.contentHash().equals(document.getContentHash())) {
                return view(document, null, repository.countExtractedPages(documentId), true);
            }
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档已完成导入，不允许替换文件内容");
        }

        repository.findByContentHash(inspected.contentHash()).ifPresent(existing -> {
            if (!existing.getDocumentId().equals(documentId)) {
                throw duplicate(existing);
            }
        });

        LocalDateTime processingAt = LocalDateTime.now();
        try {
            repository.claimForProcessing(
                    documentId,
                    inspected.fileName(),
                    null,
                    inspected.size(),
                    inspected.contentHash(),
                    inspected.content(),
                    processingAt);
        } catch (DataIntegrityViolationException ex) {
            KnowledgeDocument existing = repository.findByContentHash(inspected.contentHash())
                    .orElse(null);
            if (existing != null && !existing.getDocumentId().equals(documentId)) {
                throw duplicate(existing);
            }
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档导入抢占状态冲突",
                    ex);
        }

        try {
            KnowledgeDocumentParser.ParsedFile parsed = parser.parse(inspected);
            LocalDateTime completedAt = LocalDateTime.now();
            repository.complete(
                    documentId,
                    parsed.contentType(),
                    parsed.parsedText(),
                    parsed.pages(),
                    parsed.parserName(),
                    parsed.parserVersion(),
                    completedAt);
            KnowledgeDocument completed = requireDocument(documentId);
            log.info("知识文档导入完成，documentId={}，contentType={}，chars={}，pages={}，parser={} {}",
                    documentId,
                    parsed.contentType(),
                    parsed.characterCount(),
                    parsed.pages().size(),
                    parsed.parserName(),
                    parsed.parserVersion());
            return view(completed, parsed.characterCount(), parsed.pages().size(), false);
        } catch (RuntimeException ex) {
            try {
                repository.fail(documentId,
                        errorCode(ex),
                        safeFailureMessage(ex),
                        LocalDateTime.now());
            } catch (RuntimeException failUpdate) {
                log.error("知识文档 FAILED 状态写入失败，documentId={}，异常类型={}",
                        documentId, failUpdate.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    public KnowledgeDocumentView detail(String documentId) {
        return view(
                requireDocument(documentId),
                null,
                repository.countExtractedPages(documentId),
                false);
    }

    public KnowledgeDocumentExtractionView extraction(String documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getStatus() != KnowledgeDocumentStatus.COMPLETED) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_STATE_CONFLICT,
                    "知识文档尚未完成解析，不能读取页面结果");
        }
        List<KnowledgeDocumentPage> storedPages = repository.findExtractedPages(documentId);
        List<KnowledgeDocumentPageView> pages = storedPages.stream()
                .map(page -> new KnowledgeDocumentPageView(
                        page.pageNumber(),
                        page.content(),
                        page.characterCount(),
                        page.blankPage(),
                        page.parserName(),
                        page.parserVersion(),
                        page.updatedAt()))
                .toList();
        return new KnowledgeDocumentExtractionView(
                document.getDocumentId(),
                document.getSourceSystem(),
                document.getTitle(),
                document.getVersion(),
                document.getContentType(),
                document.getStatus().name(),
                pages.size(),
                document.getUpdatedAt(),
                pages);
    }

    private KnowledgeDocument requireDocument(String documentId) {
        return repository.findByDocumentId(documentId)
                .orElseThrow(() -> new KnowledgeDocumentException(
                        ResultCode.DOCUMENT_NOT_FOUND,
                        "知识文档不存在: " + documentId));
    }

    private KnowledgeDocumentException duplicate(KnowledgeDocument existing) {
        return new KnowledgeDocumentException(
                ResultCode.DOCUMENT_DUPLICATE,
                "相同内容已导入为文档: " + existing.getDocumentId());
    }

    private void failSafely(String documentId, RuntimeException original) {
        try {
            repository.fail(documentId, errorCode(original), safeFailureMessage(original),
                    LocalDateTime.now());
        } catch (RuntimeException updateFailure) {
            log.error("知识文档导入失败状态写入失败，documentId={}，异常类型={}",
                    documentId, updateFailure.getClass().getSimpleName());
        }
    }

    private String errorCode(RuntimeException ex) {
        if (ex instanceof KnowledgeDocumentException documentException) {
            return documentException.getResultCode().name();
        }
        return "IMPORT_FAILED";
    }

    private String safeFailureMessage(RuntimeException ex) {
        if (ex instanceof KnowledgeDocumentException && StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return "知识文档导入处理失败";
    }

    private KnowledgeDocumentView view(KnowledgeDocument document, Integer parsedChars,
                                       int pageCount, boolean duplicate) {
        return new KnowledgeDocumentView(
                document.getDocumentId(),
                document.getSourceSystem(),
                document.getTitle(),
                document.getVersion(),
                document.getStatus().name(),
                document.getUpdatedAt(),
                document.getFileName(),
                document.getContentType(),
                document.getFileSize(),
                document.getContentHash(),
                parsedChars,
                pageCount,
                document.getErrorCode(),
                document.getErrorMessage(),
                duplicate);
    }
}
