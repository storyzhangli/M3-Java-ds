package com.zl.d41.knowledge.vectorstore;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 故障注入装配（默认关闭：主 profile 永不装配，回归态天然无注入残留，H5/C18）。
 *
 * <p>触发参数含义：0=该路径永不注入；&gt;0=该路径第 N 次调用抛受控异常。
 * 测试实例经命令行 property 显式预设，可复现（KP3 冻结）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.vector-index.fault-injection.enabled", havingValue = "true")
public class FaultInjectionConfiguration {

    @Data
    public static class FaultInjectionParams {

        private boolean enabled = false;

        /** 第 N 次 vectorStore.add 抛异常；0=不注入。 */
        private long failAddCall = 0;

        /** 第 N 次 vectorStore.delete 抛异常；0=不注入。 */
        private long failDeleteCall = 0;
    }

    @Bean
    @ConfigurationProperties(prefix = "app.vector-index.fault-injection")
    public static FaultInjectionParams faultInjectionParams() {
        return new FaultInjectionParams();
    }

    @Bean
    @Primary
    public VectorStore faultInjectingVectorStore(
            @Qualifier("pgVectorStore") VectorStore delegate,
            FaultInjectionParams params) {
        return new FaultInjectingVectorStore(delegate,
                params.getFailAddCall(), params.getFailDeleteCall());
    }
}
