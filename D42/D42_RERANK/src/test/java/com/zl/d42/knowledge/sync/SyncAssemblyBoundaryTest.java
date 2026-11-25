package com.zl.d42.knowledge.sync;

import com.zl.d42.access.AccessGuard;
import com.zl.d42.access.AccessPolicyService;
import com.zl.d42.access.AuthContext;
import com.zl.d42.access.AuthContextHolder;
import com.zl.d42.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d42.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d42.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d42.knowledge.retrieval.RetrievalException;
import com.zl.d42.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d42.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d42.knowledge.vectorindex.support.ContentHasher;
import com.zl.d42.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d42.knowledge.vectorstore.PgVectorAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D40 R03-08 生产权限边界与 two_ 兼容路径装配测试（T-R03-31~36）。
 *
 * <p>证明：生产主轨缺 SyncService 时删除入口 fail-fast（不静默降级 legacy）；
 * two_ 兼容路径显式 setSyncStateRequired(false) 且不写 d42_document_sync；
 * enforce-admin 门禁在 AccessGuard 缺席时启动失败（fail-closed）；
 * 普通用户/匿名调用管理员校验被拒绝且不静默放行。</p>
 */
class SyncAssemblyBoundaryTest {

    private static final String DOC = "d42t-boundary-doc";
    private static final String VER = "1";

    private VectorStore vectorStore;
    private VectorChunkRepository chunkRepository;
    private InMemoryDocumentSyncRepository syncRepository;
    private DocumentSyncService syncService;
    private DocumentVectorIndexService indexService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        PgVectorAccess pgVectorAccess = mock(PgVectorAccess.class);
        chunkRepository = mock(VectorChunkRepository.class);
        KnowledgeDocumentRepository docMeta = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocumentChunkingService chunking = mock(KnowledgeDocumentChunkingService.class);
        EmbeddingModelDescriptor descriptor = mock(EmbeddingModelDescriptor.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(descriptor.vectorSpaceId()).thenReturn("LOCAL_HASH_384");
        when(pgVectorAccess.countByDocumentVersion(anyString(), anyString())).thenReturn(0L);

        VectorChunkRow row = new VectorChunkRow();
        row.setDocumentId(DOC);
        row.setVersion(VER);
        row.setChunkId("chunk-a");
        row.setStatus("INDEXED");
        doReturn(new ArrayList<>(List.of(row))).when(chunkRepository).findChunks(DOC, VER);
        doReturn(1).when(chunkRepository).deleteChunks(DOC, VER);
        doReturn(1).when(chunkRepository).deleteTask(DOC, VER);

        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoffMs(50);
        properties.setStuckMinutes(30);

        syncRepository = new InMemoryDocumentSyncRepository();
        syncService = new DocumentSyncService(syncRepository, properties);
        indexService = new DocumentVectorIndexService(vectorStore, pgVectorAccess,
                chunkRepository, new StableChunkIdGenerator(), new ContentHasher(),
                properties, descriptor, chunking, txManager, docMeta);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    @DisplayName("T-R03-31 生产主轨缺 SyncService：删除入口 fail-fast，不静默降级 legacy")
    void productionDeleteFailsFastWithoutSyncService() {
        // 默认 syncStateRequired=true，且不注入 syncService（模拟生产装配缺失）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> indexService.deleteByDocumentVersion(DOC, VER));
        assertTrue(ex.getMessage().contains("SyncService"));
        verify(vectorStore, org.mockito.Mockito.never()).delete(anyList());
    }

    @Test
    @DisplayName("T-R03-32 two_ 兼容路径：显式 legacy 放行删除成功")
    void twoCompatLegacyDeleteAllowed() {
        indexService.setSyncStateRequired(false);
        assertDoesNotThrow(() -> indexService.deleteByDocumentVersion(DOC, VER));
        verify(vectorStore).delete(anyList());
    }

    @Test
    @DisplayName("T-R03-33 enforce-admin 门禁：AccessGuard 缺席启动失败（fail-closed）")
    void enforceAdminFailsClosedWithoutGuard() {
        SyncAdminBoundaryConfiguration configuration = new SyncAdminBoundaryConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<AccessGuard> provider = mock(ObjectProvider.class);
        doReturn(null).when(provider).getIfAvailable();
        SmartInitializingSingleton check = configuration.adminGuardPresenceCheck(provider);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                check::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("enforce-admin"));
    }

    @Test
    @DisplayName("T-R03-34 enforce-admin 门禁：AccessGuard 在场启动通过")
    void enforceAdminPassesWithGuard() {
        SyncAdminBoundaryConfiguration configuration = new SyncAdminBoundaryConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<AccessGuard> provider = mock(ObjectProvider.class);
        doReturn(new AccessGuard(mock(AccessPolicyService.class))).when(provider).getIfAvailable();
        SmartInitializingSingleton check = configuration.adminGuardPresenceCheck(provider);
        assertDoesNotThrow(check::afterSingletonsInstantiated);
    }

    @Test
    @DisplayName("T-R03-35 管理员校验：匿名/普通用户拒绝，ADMIN 放行，不静默放行")
    void adminGuardRejectsAnonymousAndUser() {
        AccessGuard guard = new AccessGuard(mock(AccessPolicyService.class));
        // 匿名（无服务端可信身份）
        RetrievalException anonymous = assertThrows(RetrievalException.class, guard::requireAdmin);
        assertEquals("AUTH_CONTEXT_MISSING", anonymous.getErrorCode());
        // 普通用户
        AuthContextHolder.restore(new AuthContext("userA1", "T1", "D1", Set.of(), AuthContext.ROLE_USER));
        RetrievalException user = assertThrows(RetrievalException.class, guard::requireAdmin);
        assertEquals("AUTH_FORBIDDEN", user.getErrorCode());
        // 管理员
        AuthContextHolder.restore(new AuthContext("adminA1", "T1", "D-HQ", Set.of(), AuthContext.ROLE_ADMIN));
        AuthContext ctx = assertDoesNotThrow(guard::requireAdmin);
        assertTrue(ctx.isAdmin());
    }

    @Test
    @DisplayName("T-R03-36 two_ 兼容行为不污染主轨：legacy 删除不产生 d42_document_sync 行")
    void twoCompatDoesNotPolluteSyncState() {
        // two_ 手动装配语义：不注入 syncService + 显式 legacy 声明（与 TwoPgVectorConfiguration 一致）
        indexService.setSyncStateRequired(false);
        indexService.deleteByDocumentVersion(DOC, VER);
        assertTrue(syncService.find(DOC, SyncOperation.DELETE, VER).isEmpty(),
                "two_ legacy 通道不得写入主轨同步状态");
        assertTrue(syncService.find(DOC, SyncOperation.UPDATE_CLEANUP, VER).isEmpty());
    }
}
