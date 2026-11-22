package com.zl.d39.hybrid.config;

import com.zl.d39.hybrid.config.HybridProperties;
import com.zl.d39.hybrid.keyword.KeywordTokenizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 混合检索分支装配（P2.4/P2.5，沿用 R01 two_ 方案 B 先例）。
 *
 * <p>隔离要点：</p>
 * <ul>
 *   <li>不新增 EmbeddingModel / VectorStore / RagOrchestrator / RagRetrievalService 类型
 *       Bean——第二编排器与其检索通道在 {@code HybridRagController} 构造器内手动 new、
 *       局部持有，容器内按类型注入点保持单候选，主链路（LOCAL/two_）行为零变更（H1/H2）；</li>
 *   <li>整个配置类与 {@code HybridProperties} 均挂
 *       {@code @ConditionalOnProperty(name="app.hybrid.enabled", havingValue="true")}，
 *       不设 matchIfMissing：默认关闭时 hybrid 全部 Bean 不装配（C04）；</li>
 *   <li>倒排加载 fail-fast：enabled=true 且 PG 不可达时启动失败（§3.7，无内存 fallback）。</li>
 * </ul>
 *
 * <p>R02 实例 B 真实启动修正：原 {@code hybridRagController} {@code @Bean} 工厂已删除——
 * 裸 @RequestMapping 类不被 RequestMappingHandlerMapping 识别（isHandler 仅认
 * {@code @Controller}/{@code @RestController}），控制器改为类级条件化组件扫描注册
 * （见 HybridRagController 类注释）。RrfFusionService/KeywordSearchService/
 * HybridSearchService/HybridSearchController 亦走各自类级条件注解注册。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class HybridConfiguration {

    /** 分词器：特征子集来自冻结配置（装配期校验，非法值 fail-fast）。 */
    @Bean
    public KeywordTokenizer hybridKeywordTokenizer(HybridProperties properties) {
        properties.validate();
        return new KeywordTokenizer(properties.getFeatureSubset());
    }
}
