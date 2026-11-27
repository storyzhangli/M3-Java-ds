package com.zl.d44.knowledge.document;

import com.zl.d44.common.ResultCode;
import com.zl.d44.knowledge.document.KnowledgeDocumentParser.InspectedFile;
import com.zl.d44.knowledge.document.KnowledgeDocumentParser.ParsedFile;
import com.zl.d44.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d44.knowledge.document.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3：生命周期 Service 写方法守卫测试（markDeleted 幂等 / publishNewVersion 三守卫+版本 int 化+失败回写）。
 *
 * <p>仓储与解析器全 Mock，纯单元层；HTTP 编排与审计断言见
 * KnowledgeDocumentLifecycleControllerTest，真实库闭环由 P5 e2e 场景轮承载。</p>
 */
class KnowledgeDocumentLifecycleServiceTest {

    private static final String DOC = "d44t-lifecycle-doc";

    private KnowledgeDocumentRepository repository;
    private KnowledgeDocumentParser parser;
    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeDocumentRepository.class);
        parser = mock(KnowledgeDocumentParser.class);
        service = new KnowledgeDocumentService(repository, parser);
    }

    private KnowledgeDocument document(KnowledgeDocumentStatus status, String version, String hash) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId(DOC);
        doc.setSourceSystem("manual");
        doc.setTitle("生命周期测试文档");
        doc.setVersion(version);
        doc.setStatus(status);
        doc.setFileName("old.txt");
        doc.setContentType("text/plain");
        doc.setFileSize(8L);
        doc.setContentHash(hash);
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }

    private void stubFind(KnowledgeDocument doc) {
        when(repository.findByDocumentId(DOC)).thenReturn(Optional.of(doc));
    }

    @Test
    @DisplayName("markDeleted：ACTIVE 首删写 DELETED 并返回终态视图")
    void markDeletedFirstTime() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "1", "hash-old"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("ACTIVE", "DELETED");
        when(repository.countExtractedPages(DOC)).thenReturn(3);

        KnowledgeDocumentView view = service.markDeleted(DOC);

        verify(repository).markLifecycleStatus(eq(DOC), eq("DELETED"), any(LocalDateTime.class));
        assertEquals("COMPLETED", view.status());
        assertEquals(3, view.pageCount());
    }

    @Test
    @DisplayName("markDeleted：已 DELETED 幂等，不重复写库")
    void markDeletedIdempotent() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "1", "hash-old"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("DELETED");
        when(repository.countExtractedPages(DOC)).thenReturn(3);

        service.markDeleted(DOC);

        verify(repository, never()).markLifecycleStatus(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("publishNewVersion：非 COMPLETED 拒绝（DOCUMENT_STATE_CONFLICT）")
    void publishRejectNonCompleted() {
        stubFind(document(KnowledgeDocumentStatus.PROCESSING, "1", "hash-old"));
        var ex = assertThrows(KnowledgeDocumentException.class,
                () -> service.publishNewVersion(DOC, new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));
        assertEquals(ResultCode.DOCUMENT_STATE_CONFLICT, ex.getResultCode());
    }

    @Test
    @DisplayName("publishNewVersion：DELETED 文档拒绝发布")
    void publishRejectDeleted() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "1", "hash-old"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("DELETED");
        var ex = assertThrows(KnowledgeDocumentException.class,
                () -> service.publishNewVersion(DOC, new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));
        assertEquals(ResultCode.DOCUMENT_STATE_CONFLICT, ex.getResultCode());
    }

    @Test
    @DisplayName("publishNewVersion：内容 hash 未变化拒绝发布")
    void publishRejectSameHash() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "1", "same-hash"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("ACTIVE");
        when(parser.inspect(any())).thenReturn(new InspectedFile("n.txt", "txt", 1L, "same-hash", new byte[]{1}));
        var ex = assertThrows(KnowledgeDocumentException.class,
                () -> service.publishNewVersion(DOC, new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));
        assertEquals(ResultCode.DOCUMENT_STATE_CONFLICT, ex.getResultCode());
        verify(repository, never()).republishClaim(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("publishNewVersion：claim→complete→指针 int+1 顺序发布")
    void publishIncrementsVersionPointer() {
        KnowledgeDocument doc = document(KnowledgeDocumentStatus.COMPLETED, "1", "hash-old");
        stubFind(doc);
        when(repository.findLifecycleStatus(DOC)).thenReturn("ACTIVE");
        when(parser.inspect(any())).thenReturn(new InspectedFile("n.txt", "txt", 3L, "hash-new", new byte[]{1, 2, 3}));
        when(parser.parse(any(InspectedFile.class))).thenReturn(
                new ParsedFile("text/plain", "abc", List.of(), "txt-parser", "1.0"));
        when(repository.countExtractedPages(DOC)).thenReturn(1);

        KnowledgeDocumentView view = service.publishNewVersion(DOC,
                new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1, 2, 3}));

        ArgumentCaptor<String> versionCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateVersionPointer(eq(DOC), versionCaptor.capture(), any(LocalDateTime.class));
        assertEquals("2", versionCaptor.getValue());
        InOrder claimOrder = inOrder(repository);
        claimOrder.verify(repository).republishClaim(eq(DOC), eq("n.txt"), isNull(), eq(3L), eq("hash-new"), any(), any());
        claimOrder.verify(repository).complete(eq(DOC), eq("text/plain"), eq("abc"), anyList(), eq("txt-parser"), eq("1.0"), any());
        claimOrder.verify(repository).updateVersionPointer(eq(DOC), eq("2"), any());
        assertNotNull(view);
    }

    @Test
    @DisplayName("publishNewVersion：当前版本非整数拒绝（PARAM_INVALID）且不落任何写库")
    void publishRejectNonNumericVersion() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "v1", "hash-old"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("ACTIVE");
        when(parser.inspect(any())).thenReturn(new InspectedFile("n.txt", "txt", 1L, "hash-new", new byte[]{1}));
        var ex = assertThrows(KnowledgeDocumentException.class,
                () -> service.publishNewVersion(DOC, new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
        verify(repository, never()).republishClaim(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("publishNewVersion：写库异常回写失败态并原样上抛")
    void publishFailureWritesFailState() {
        stubFind(document(KnowledgeDocumentStatus.COMPLETED, "1", "hash-old"));
        when(repository.findLifecycleStatus(DOC)).thenReturn("ACTIVE");
        when(parser.inspect(any())).thenReturn(new InspectedFile("n.txt", "txt", 1L, "hash-new", new byte[]{1}));
        when(parser.parse(any(InspectedFile.class))).thenReturn(
                new ParsedFile("text/plain", "abc", List.of(), "txt-parser", "1.0"));
        doThrow(new IllegalStateException("db down")).when(repository)
                .republishClaim(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.publishNewVersion(DOC,
                new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));
        verify(repository).fail(eq(DOC), any(), anyString(), any(LocalDateTime.class));
        verify(repository, never()).updateVersionPointer(anyString(), anyString(), any());
    }
}