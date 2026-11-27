package com.zl.d44.knowledge.document;

import com.zl.d44.common.ResultCode;
import com.zl.d44.knowledge.document.KnowledgeDocument;
import com.zl.d44.knowledge.document.KnowledgeDocumentException;
import com.zl.d44.knowledge.document.KnowledgeDocumentPage;
import com.zl.d44.knowledge.document.KnowledgeDocumentParser;
import com.zl.d44.knowledge.document.KnowledgeDocumentStatus;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentExtractionView;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentPageView;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentRegistrationRequest;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d44.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d44.knowledge.table.TableRecordService;
import com.zl.d44.knowledge.table.dto.TableExtractSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * P2 追加式表格分支钩子（D1 裁决：独立处理器，不触碰页文本/状态机/错误码）。
     * ObjectProvider 字段注入零签名改动；table.enabled=false 时 Bean 缺席=零差异（H1/H2）。
     */
    @Autowired
    private ObjectProvider<TableRecordService> tableRecordProvider;

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
        // 权限三键透传（未传保持 null=权限空间外，禁止补默认值）
        document.setTenantId(blankToNull(request.getTenantId()));
        document.setDeptId(blankToNull(request.getDeptId()));
        document.setDeviceId(blankToNull(request.getDeviceId()));
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
            extractTableRecordsQuietly(documentId, completed.getVersion(), inspected, parsed);
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

    /**
     * P2 表格结构化抽取钩子：table.enabled=false 时 Bean 缺席零调用；
     * 任何失败仅告警降级，绝不阻断文档 COMPLETED（H1 追加式边界）。
     */
    private void extractTableRecordsQuietly(String documentId, String version,
                                            KnowledgeDocumentParser.InspectedFile inspected,
                                            KnowledgeDocumentParser.ParsedFile parsed) {
        TableRecordService service = tableRecordProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            TableExtractSummary summary = service.extractAndPersist(documentId, version,
                    inspected.extension(), inspected.content(),
                    parsed.pages(), parsed.parsedText());
            log.info("表格结构化记录抽取完成，documentId={}，version={}，tables={}，rows={}，"
                            + "linked={}，linkageMissing={}，degraded={}",
                    documentId, version, summary.tables(), summary.rows(),
                    summary.linkageHitTables(), summary.linkageMissingTables(),
                    summary.degradedCodes());
        } catch (RuntimeException ex) {
            log.warn("表格结构化记录抽取失败（不影响文档状态机），documentId={}，异常类型={}",
                    documentId, ex.getClass().getSimpleName());
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

    /**
     * 按 documentId 读取权限范围三键（供文档级访问校验；不存在时抛既有 NOT_FOUND）。
     */
    public KnowledgeDocument documentForScope(String documentId) {
        return requireDocument(documentId);
    }

    /** 空串/空白归一为 null（未传=无标签=权限空间外），其余 strip 后保留。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
                duplicate,
                document.getTenantId(),
                document.getDeptId(),
                document.getDeviceId());
    }
    /**
     * P3（D1 裁决）：标记删除——lifecycle_status=DELETED（幂等：已 DELETED 直接返回终态视图）。
     * 向量清除由调用方编排 DocumentVectorIndexService.deleteByDocument（D40 状态机闭环），本方法不越层。
     */
    public KnowledgeDocumentView markDeleted(String documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        if ("DELETED".equalsIgnoreCase(repository.findLifecycleStatus(documentId))) {
            return view(document, null, repository.countExtractedPages(documentId), false);
        }
        repository.markLifecycleStatus(documentId, "DELETED", LocalDateTime.now());
        KnowledgeDocument deleted = requireDocument(documentId);
        log.info("知识文档已标记删除 documentId={}", documentId);
        return view(deleted, null, repository.countExtractedPages(documentId), false);
    }

    /**
     * P3：发布新版本（内容替换 + 版本指针 int+1）。
     * 守卫：COMPLETED 且非 DELETED 且 hash 有变化；向量侧新写→SWITCH→旧清理由调用方
     * 编排 DocumentVectorIndexService.indexFromPages（版本取本方法提交后的新指针）。
     */
    public KnowledgeDocumentView publishNewVersion(String documentId, MultipartFile file) {
        KnowledgeDocument document = requireDocument(documentId);
        if (document.getStatus() != KnowledgeDocumentStatus.COMPLETED) {
            throw new KnowledgeDocumentException(ResultCode.DOCUMENT_STATE_CONFLICT,
                    "仅已完成导入的文档可发布新版本，当前状态=" + document.getStatus());
        }
        if ("DELETED".equalsIgnoreCase(repository.findLifecycleStatus(documentId))) {
            throw new KnowledgeDocumentException(ResultCode.DOCUMENT_STATE_CONFLICT,
                    "已标记删除的文档不允许发布新版本");
        }
        KnowledgeDocumentParser.InspectedFile inspected = parser.inspect(file);
        if (inspected.contentHash().equals(document.getContentHash())) {
            throw new KnowledgeDocumentException(ResultCode.DOCUMENT_STATE_CONFLICT,
                    "新文件内容与当前版本一致，无需发布新版本");
        }
        long nextVersion;
        try {
            nextVersion = Long.parseLong(document.getVersion().strip()) + 1;
        } catch (RuntimeException ex) {
            throw new KnowledgeDocumentException(ResultCode.PARAM_INVALID,
                    "当前版本号非整数，无法自动递增: " + document.getVersion());
        }
        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(inspected);
        LocalDateTime claimAt = LocalDateTime.now();
        try {
            repository.republishClaim(documentId, inspected.fileName(), null,
                    inspected.size(), inspected.contentHash(), inspected.content(), claimAt);
            LocalDateTime completedAt = LocalDateTime.now();
            repository.complete(documentId, parsed.contentType(), parsed.parsedText(),
                    parsed.pages(), parsed.parserName(), parsed.parserVersion(), completedAt);
            repository.updateVersionPointer(documentId, String.valueOf(nextVersion), completedAt);
        } catch (RuntimeException ex) {
            try {
                repository.fail(documentId, errorCode(ex), safeFailureMessage(ex), LocalDateTime.now());
            } catch (RuntimeException failUpdate) {
                log.error("新版本发布失败态写入失败 documentId={} 类型={}",
                        documentId, failUpdate.getClass().getSimpleName());
            }
            throw ex;
        }
        KnowledgeDocument published = requireDocument(documentId);
        log.info("知识文档新版本已发布 documentId={} version={} pages={}",
                documentId, published.getVersion(), parsed.pages().size());
        return view(published, parsed.characterCount(), parsed.pages().size(), false);
    }
}