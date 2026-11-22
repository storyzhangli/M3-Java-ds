package com.zl.d39.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份原语单测：SHA-256 冻结向量 + AuthContext 不可变性 + Holder 快照/线程隔离（D7）。
 */
class AuthIdentityPrimitivesTest {

    // ---- AuthTokenHasher（C20 通道：全链路只触碰哈希）----

    @Test
    @DisplayName("SHA-256 已知向量与 64 位小写 hex 形态冻结")
    void sha256KnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AuthTokenHasher.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                AuthTokenHasher.sha256Hex(""));
        String hex = AuthTokenHasher.sha256Hex("d39-tok-sample");
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("确定性：同输入同输出；不同输入必不同（防碰撞夹具）")
    void deterministicAndDistinct() {
        String a = AuthTokenHasher.sha256Hex("token-a");
        assertEquals(a, AuthTokenHasher.sha256Hex("token-a"));
        assertFalse(a.equals(AuthTokenHasher.sha256Hex("token-b")));
        // 中文/UTF-8 边界稳定
        assertNotNull(AuthTokenHasher.sha256Hex("令牌\u00e9"));
        assertEquals(AuthTokenHasher.sha256Hex("x"),
                AuthTokenHasher.sha256Hex(new String("x".getBytes(StandardCharsets.UTF_8))));
    }

    // ---- AuthContext 不可变性（H3：请求体无法伪造上下文结构）----

    @Test
    @DisplayName("deviceScopes 拷贝防御：外部集合变更不影响上下文；null 归一空集")
    void contextIsImmutable() {
        Set<String> mutable = new HashSet<>();
        mutable.add("DEV-A");
        AuthContext context = new AuthContext("u", "T1", "D1", mutable, AuthContext.ROLE_USER);
        mutable.add("DEV-EVIL");
        assertEquals(Set.of("DEV-A"), context.deviceScopes());
        assertThrows(UnsupportedOperationException.class,
                () -> context.deviceScopes().add("X"));
        assertEquals(Set.of(), new AuthContext("u", "T1", "D1", null, "USER").deviceScopes());
    }

    @Test
    @DisplayName("ADMIN 判定唯一来源=role 字段等值 USER/ADMIN")
    void roleSemantics() {
        assertTrue(new AuthContext("u", "T1", "D1", Set.of(), AuthContext.ROLE_ADMIN).isAdmin());
        assertFalse(new AuthContext("u", "T1", "D1", Set.of(), AuthContext.ROLE_USER).isAdmin());
        assertFalse(new AuthContext("u", "T1", "D1", Set.of(), "admin").isAdmin(), "小写不豁免");
        assertFalse(new AuthContext("u", "T1", "D1", Set.of(), null).isAdmin());
    }

    // ---- AuthContextHolder：请求线程语义 + 快照/恢复（SSE 异步纪律 D7）----

    @Test
    @DisplayName("set/get/clear 基本循环；未设置时 get=null（关闭态镜像）")
    void holderLifecycle() {
        assertNull(AuthContextHolder.get());
        AuthContext context = new AuthContext("u", "T1", "D1", Set.of(), AuthContext.ROLE_USER);
        AuthContextHolder.restore(context);
        assertEquals(context, AuthContextHolder.get());
        assertEquals(context, AuthContextHolder.snapshot());
        AuthContextHolder.clear();
        assertNull(AuthContextHolder.get());
        AuthContextHolder.restore(null);
        assertNull(AuthContextHolder.get());
    }

    @Test
    @DisplayName("ThreadLocal 不跨线程自动传播；显式快照+恢复才可见（异步分支纪律）")
    void noImplicitPropagationExplicitSnapshotWorks() throws Exception {
        AuthContext context = new AuthContext("u", "T1", "D1", Set.of("DEV-A"), AuthContext.ROLE_USER);
        AuthContextHolder.restore(context);
        try {
            AtomicReference<AuthContext> implicitAtStart = new AtomicReference<>();
            AtomicReference<AuthContext> afterRestore = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                implicitAtStart.set(AuthContextHolder.get());          // 起点：应为 null（未传播）
                AuthContextHolder.restore(AuthContextHolder.snapshot() == null
                        ? context : AuthContextHolder.snapshot());    // 模拟请求线程 snapshot() 传入后 restore
                afterRestore.set(AuthContextHolder.get());            // 恢复后可见
                AuthContextHolder.clear();
            });
            // 主线程先做 snapshot()（SSE 装配点的真实动作），worker 只消费快照值
            AuthContext snapshot = AuthContextHolder.snapshot();
            assertNotNull(snapshot);
            worker.start();
            worker.join();
            assertNull(implicitAtStart.get(), "异步线程不得隐式继承 ThreadLocal");
            assertEquals(snapshot, afterRestore.get(), "显式快照恢复后必须可见且同值");
            // 主线程不受子线程 clear 影响
            assertEquals(context, AuthContextHolder.get());
        } finally {
            AuthContextHolder.clear();
        }
    }
}
