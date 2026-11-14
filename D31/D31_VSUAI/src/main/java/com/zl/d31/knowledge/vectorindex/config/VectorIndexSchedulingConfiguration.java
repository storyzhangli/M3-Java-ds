package com.zl.d31.knowledge.vectorindex.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：仅当 app.vector-index.reconcile-enabled=true 时启用调度，
 * 避免开发/测试环境默认发起跨库核对。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.vector-index.reconcile-enabled", havingValue = "true")
public class VectorIndexSchedulingConfiguration {
}
