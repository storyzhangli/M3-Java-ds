package com.zl.d43.knowledge.document;

import com.zl.d43.access.AccessGuard;
import com.zl.d43.knowledge.retrieval.RetrievalException;
import com.zl.d43.access.AuthContext;
import com.zl.d43.common.ResultCode;
import com.zl.d43.knowledge.document.dto.KnowledgeDocumentView;
import com.zl.d43.knowledge.sync.AuditService;
import com.zl.d43.knowledge.vectorindex.service.DocumentVectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D43 P3：生命周期 Controller 编排测试——开发放行态（guard 缺席零审计）、ADMIN 拒绝传播、
 * 成功/失败审计动作与 reasonCode、删除两步顺序、发布后向量重建调用（H2/H7/H8 单元面）。
 */
class KnowledgeDocumentLifecycleControllerTest {

    private static final String DOC = "d43t-lifecycle-doc";

    private KnowledgeDocumentService documentService;
    private DocumentVectorIndexService indexService;
    private ObjectProvider<AccessGuard> guardProvider;
    private ObjectProvider<AuditService> auditProvider;
    private AccessGuard guard;
    private AuditService audit;
    private KnowledgeDocumentLifecycleController controller;

    private final AuthContext admin = new AuthContext("u-admin", "T-A", "D-A1", Set.of(), AuthContext.ROLE_ADMIN);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentService = mock(KnowledgeDocumentService.class);
        indexService = mock(DocumentVectorIndexService.class);
        guardProvider = mock(ObjectProvider.class);
        auditProvider = mock(ObjectProvider.class);
        guard = mock(AccessGuard.class);
        audit = mock(AuditService.class);
        controller = new KnowledgeDocumentLifecycleController(
                documentService, indexService, guardProvider, auditProvider);
    }

    private KnowledgeDocumentView view(String version) {
        return new KnowledgeDocumentView(DOC, "manual", "t", version, "COMPLETED", null,
                "f.txt", "text/plain", 1L, "h", 10, 1, null, null, false, "T-A", "D-A1", null);
    }

    @Test
    @DisplayName("删除：guard 缺席=开发放行，成功且不落审计")
    void deleteDevModeNoGuardNoAudit() {
        when(guardProvider.getIfAvailable()).thenReturn(null);
        when(auditProvider.getIfAvailable()).thenReturn(null);
        when(documentService.markDeleted(DOC)).thenReturn(view("1"));
        when(documentService.detail(DOC)).thenReturn(view("1"));

        var result = controller.delete(DOC);

        assertEquals(200, result.getCode());
        verify(indexService).deleteByDocument(DOC);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("删除：开启态先 markDeleted 后 deleteByDocument，成功落 DOC_DELETE 审计")
    void deleteOrderedAndAudited() {
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(guard.requireAdmin()).thenReturn(admin);
        when(documentService.markDeleted(DOC)).thenReturn(view("1"));
        when(documentService.detail(DOC)).thenReturn(view("1"));

        controller.delete(DOC);

        var ordered = inOrder(documentService, indexService, audit);
        ordered.verify(documentService).markDeleted(DOC);
        ordered.verify(indexService).deleteByDocument(DOC);
        ordered.verify(audit).recordAllowed(admin, "DOC_DELETE", DOC, "1");
    }

    @Test
    @DisplayName("删除：ADMIN 拒绝异常原样传播，业务零调用")
    void deleteForbiddenPropagates() {
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(guard.requireAdmin()).thenThrow(new RetrievalException(
                ResultCode.PARAM_INVALID, "该操作仅限管理员", "AUTH_FORBIDDEN"));

        var ex = assertThrows(RetrievalException.class, () -> controller.delete(DOC));
        assertEquals("AUTH_FORBIDDEN", ex.getErrorCode());
        verifyNoInteractions(documentService, indexService);
    }

    @Test
    @DisplayName("删除：向量闭环失败落 recordFailed(LIFECYCLE_OP_FAILED) 并上抛")
    void deleteFailureAuditsFailed() {
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(guard.requireAdmin()).thenReturn(admin);
        when(documentService.markDeleted(DOC)).thenReturn(view("1"));
        doThrow(new IllegalStateException("pg down")).when(indexService).deleteByDocument(DOC);

        assertThrows(IllegalStateException.class, () -> controller.delete(DOC));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).recordFailed(eq(admin), eq("DOC_DELETE"), eq(DOC), eq("1"), reason.capture());
        assertEquals("LIFECYCLE_OP_FAILED", reason.getValue());
        verify(audit, never()).recordAllowed(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("发布新版本：publish 后触发 indexFromPages 重建，成功落 DOC_VERSION_PUBLISH")
    void publishReindexesAndAudits() {
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(guard.requireAdmin()).thenReturn(admin);
        when(documentService.publishNewVersion(eq(DOC), any())).thenReturn(view("2"));
        when(documentService.detail(DOC)).thenReturn(view("2"));

        var result = controller.publishVersion(DOC,
                new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1}));

        assertEquals("2", result.getData().version());
        var ordered = inOrder(documentService, indexService, audit);
        ordered.verify(documentService).publishNewVersion(eq(DOC), any());
        ordered.verify(indexService).indexFromPages(DOC, null);
        ordered.verify(audit).recordAllowed(admin, "DOC_VERSION_PUBLISH", DOC, "2");
    }

    @Test
    @DisplayName("发布新版本：状态守卫拒绝时落失败审计并上抛")
    void publishFailureAuditsFailed() {
        when(guardProvider.getIfAvailable()).thenReturn(guard);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(guard.requireAdmin()).thenReturn(admin);
        when(documentService.publishNewVersion(eq(DOC), any())).thenThrow(new KnowledgeDocumentException(
                ResultCode.DOCUMENT_STATE_CONFLICT, "已标记删除的文档不允许发布新版本"));

        assertThrows(KnowledgeDocumentException.class, () -> controller.publishVersion(DOC,
                new MockMultipartFile("file", "n.txt", "text/plain", new byte[]{1})));

        verify(indexService, never()).indexFromPages(anyString(), any());
        verify(audit).recordFailed(eq(admin), eq("DOC_VERSION_PUBLISH"), eq(DOC), eq(""), eq("LIFECYCLE_OP_FAILED"));
    }
}