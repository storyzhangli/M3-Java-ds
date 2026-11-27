package com.zl.d44.knowledge.sync;

import com.zl.d44.knowledge.sync.DocumentSyncRow;
import com.zl.d44.knowledge.sync.DocumentSyncService;
import com.zl.d44.knowledge.sync.SyncOperation;

/**
 * 同步重试处理器：由具体管线（删除闭环 / 旧版本清理）实现并向 {@link DocumentSyncService}
 * 注册，使状态机与执行管线解耦、避免 Spring 构造循环依赖
 * （管线侧经 @PostConstruct 注册，sync 服务不反向依赖管线 Bean）。
 */
public interface SyncRetryHandler {

    /** 本处理器负责收敛的操作类型。 */
    SyncOperation operation();

    /**
     * 幂等重试执行：重放该操作的完整闭环（含双端确认），
     * 成功须调用 markSynced，失败须调用 markFailed；重复执行安全。
     */
    void execute(DocumentSyncRow row);
}
