package com.zl.d34.knowledge.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档证据生命周期可用性判定单元测试：覆盖状态/生效时间/过期时间的组合边界。
 */
class DocumentLifecycleStatusTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    @DisplayName("ACTIVE 且已生效未过期 → 可用")
    void activeWithinWindowIsUsable() {
        assertThat(DocumentLifecycleStatus.ACTIVE.isUsableForEvidence(
                NOW, NOW.minusDays(1), NOW.plusDays(1))).isTrue();
    }

    @Test
    @DisplayName("ACTIVE 但未到生效时间 → 不可用")
    void activeBeforeEffectiveIsNotUsable() {
        assertThat(DocumentLifecycleStatus.ACTIVE.isUsableForEvidence(
                NOW, NOW.plusMinutes(1), null)).isFalse();
    }

    @Test
    @DisplayName("ACTIVE 但已过过期时间 → 不可用")
    void activeAfterExpiryIsNotUsable() {
        assertThat(DocumentLifecycleStatus.ACTIVE.isUsableForEvidence(
                NOW, null, NOW.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("effectiveAt/expiresAt 为空视为立即生效且不设过期")
    void nullTimesMeanOpenWindow() {
        assertThat(DocumentLifecycleStatus.ACTIVE.isUsableForEvidence(NOW, null, null)).isTrue();
    }

    @Test
    @DisplayName("DRAFT/DEPRECATED/EXPIRED 任何时间都不可用")
    void nonActiveStatusesNeverUsable() {
        assertThat(DocumentLifecycleStatus.DRAFT.isUsableForEvidence(NOW, null, null)).isFalse();
        assertThat(DocumentLifecycleStatus.DEPRECATED.isUsableForEvidence(NOW, null, null)).isFalse();
        assertThat(DocumentLifecycleStatus.EXPIRED.isUsableForEvidence(
                NOW, NOW.minusDays(2), NOW.plusDays(2))).isFalse();
    }
}
