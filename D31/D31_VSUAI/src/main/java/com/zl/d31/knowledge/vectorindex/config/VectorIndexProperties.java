package com.zl.d31.knowledge.vectorindex.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量索引编排配置（前缀 {@code app.vector-index}）。
 *
 * <p>集中管理批大小、默认切分策略、重试上限、卡住判定与定时核对参数，
 * 不在业务代码中散落魔数。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.vector-index")
public class VectorIndexProperties {

    /** 默认切分策略（ChunkStrategy 枚举名），与 chunking 模块保持一致。 */
    private String defaultStrategy = "FIXED_LENGTH";

    /** 单批写入 PG 的 Chunk 数；批越小越容易复现部分失败。 */
    private int batchSize = 100;

    /** 单文档版本最大尝试次数（含首次），达到后标记最终失败并告警。 */
    private int maxAttempts = 5;

    /** 判定 INDEXING/PENDING 任务卡住的分钟数（崩溃恢复口径）。 */
    private int stuckMinutes = 30;

    /** 重试基础退避（毫秒），仅用于记录 next_retry_at，不阻塞线程。 */
    private long retryBackoffMs = 500;

    /** 切分算法版本：纳入稳定 Chunk ID，算法变更时旧 ID 不复用。 */
    private String splitVersion = "split-v1";

    /** 是否启用定时核对（默认关闭，避免测试与本地环境产生额外 DB 压力）。 */
    private boolean reconcileEnabled = false;

    /** 定时核对 Cron（Spring 6 位，默认每日 02:00）。 */
    private String reconcileCron = "0 0 2 * * *";

    @PostConstruct
    void checkOnStartup() {
        validate();
    }

    public void validate() {
        if (batchSize < 1) {
            throw new IllegalArgumentException("vector-index.batch-size 必须为正");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("vector-index.max-attempts 必须在 1..20");
        }
        if (stuckMinutes < 1) {
            throw new IllegalArgumentException("vector-index.stuck-minutes 必须为正");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("vector-index.retry-backoff-ms 不能为负");
        }
        if (splitVersion == null || splitVersion.isBlank()) {
            throw new IllegalArgumentException("vector-index.split-version 不能为空");
        }
        if (defaultStrategy == null || defaultStrategy.isBlank()) {
            throw new IllegalArgumentException("vector-index.default-strategy 不能为空");
        }
        if (reconcileCron == null || reconcileCron.isBlank()) {
            throw new IllegalArgumentException("vector-index.reconcile-cron 不能为空");
        }
    }
}
