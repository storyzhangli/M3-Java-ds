package com.zl.d30.knowledge.vectorstore;

import com.zl.d30.knowledge.embedding.DistanceMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgVectorStoreProperties 配置校验单元测试（纯 POJO，无 Spring 上下文、无数据库）。
 */
class PgVectorStorePropertiesTest {

    private PgVectorStoreProperties defaults() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setUrl("jdbc:postgresql://192.168.200.153:5432/spring_ai");
        properties.setUsername("postgres");
        return properties;
    }

    @Test
    @DisplayName("默认值满足 D30 约束：384 维 / COSINE / HNSW / 不建表 / 不删表")
    void defaultsMatchD30Contract() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();

        assertThat(properties.getDimensions()).isEqualTo(384);
        assertThat(properties.getDistanceMetric()).isEqualTo(DistanceMetric.COSINE);
        assertThat(properties.getIndexType()).isEqualTo(PgVectorIndexType.HNSW);
        assertThat(properties.getSchemaName()).isEqualTo("public");
        assertThat(properties.getTableName()).isEqualTo("vector_store");
        assertThat(properties.isInitializeSchema()).isFalse();
        assertThat(properties.isRemoveExistingVectorStoreTable()).isFalse();
    }

    @Test
    @DisplayName("URL 为空时 requireValid 拒绝")
    void requireValidRejectsBlankUrl() {
        PgVectorStoreProperties properties = defaults();
        properties.setUrl(" ");

        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    @DisplayName("initialize-schema=true 被拒绝：表结构必须由外部幂等 DDL 管理")
    void requireValidRejectsInitializeSchema() {
        PgVectorStoreProperties properties = defaults();
        properties.setInitializeSchema(true);

        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialize-schema 必须为 false");
    }

    @Test
    @DisplayName("remove-existing-vector-store-table=true 被拒绝：禁止删除已有向量数据")
    void requireValidRejectsRemoveExistingTable() {
        PgVectorStoreProperties properties = defaults();
        properties.setRemoveExistingVectorStoreTable(true);

        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remove-existing-vector-store-table 必须为 false");
    }

    @Test
    @DisplayName("表名含危险字符被拒绝：防止 regclass/SQL 注入")
    void requireValidRejectsUnsafeIdentifier() {
        PgVectorStoreProperties properties = defaults();
        properties.setTableName("vector_store; DROP TABLE vector_store;--");

        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }
}
