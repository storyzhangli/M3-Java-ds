package com.zl.d44.knowledge.document;

import com.zl.d44.knowledge.document.KnowledgeDocumentStatus;

import java.time.LocalDateTime;

/**
 * 知识文档证据生命周期状态（导入状态 {@link KnowledgeDocumentStatus} 之外的证据可用性维度）。
 *
 * <p>证据可用性判定由 {@link #isUsableForEvidence} 统一收口：
 * 仅 ACTIVE 且已生效、未过期时可用；DEPRECATED/EXPIRED/未生效（DRAFT 或生效时间未到）不可用。</p>
 */
public enum DocumentLifecycleStatus {

    /** 草稿：尚未生效，不得作为证据。 */
    DRAFT,

    /** 生效中：可作为证据。 */
    ACTIVE,

    /** 已废弃：被新版本替代，不得作为证据。 */
    DEPRECATED,

    /** 已过期：超出生效期，不得作为证据。 */
    EXPIRED;

    /**
     * 证据可用性判定（统一口径，评估与检索过滤共用）。
     *
     * @param now         判定时刻
     * @param effectiveAt 生效时间（null 视为立即生效）
     * @param expiresAt   过期时间（null 视为不设过期）
     */
    public boolean isUsableForEvidence(LocalDateTime now, LocalDateTime effectiveAt,
                                       LocalDateTime expiresAt) {
        if (this != ACTIVE) {
            return false;
        }
        if (effectiveAt != null && now.isBefore(effectiveAt)) {
            return false;
        }
        return expiresAt == null || !now.isAfter(expiresAt);
    }
}
