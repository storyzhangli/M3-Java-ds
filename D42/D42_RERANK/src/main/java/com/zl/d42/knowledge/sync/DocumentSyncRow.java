package com.zl.d42.knowledge.sync;

import com.zl.d42.knowledge.sync.SyncReasonCode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * d42_document_sync 行投影（操作级同步状态机，JdbcTemplate 参数化读写）。
 *
 * <p>自然键 (document_id, operation, version)：version 以空串参与唯一键
 * （MySQL uk 对 NULL 不去重，冻结裁决见 sync-params.json）。</p>
 */
@Data
public class DocumentSyncRow {

    private long id;

    private String documentId;

    /** {@link SyncOperation} 枚举名。 */
    private String operation;

    /** 参与自然键；空串表示文档级操作。 */
    private String version;

    /** {@link SyncStatus} 枚举名。 */
    private String syncStatus;

    /** {@link SyncReasonCode} 或空串。 */
    private String reasonCode;

    private int attemptCount;

    private LocalDateTime nextRetryAt;

    /** 载荷指纹（DELETE=目标 chunkId 排序 SHA-256；UPDATE_CLEANUP=残留行指纹）。 */
    private String payloadHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
