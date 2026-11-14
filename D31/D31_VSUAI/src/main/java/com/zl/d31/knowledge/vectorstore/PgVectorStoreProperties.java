package com.zl.d31.knowledge.vectorstore;

import com.zl.d31.knowledge.embedding.DistanceMetric;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.regex.Pattern;

/**
 * 外部化 PostgreSQL/pgvector 向量存储配置。
 *
 * <p>连接凭据只允许通过环境变量注入（PG_VECTOR_URL / PG_VECTOR_USERNAME /
 * PG_VECTOR_PASSWORD），password 字段不参与 Lombok toString，禁止写入日志、证据与 Git。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.vectorstore.pgvector")
public class PgVectorStoreProperties {

    /** schema/table 标识符白名单：仅允许字母、数字、下划线，且字母或下划线开头。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @NotBlank
    private String url;

    @NotBlank
    private String username;

    @ToString.Exclude
    private String password = "";

    @NotBlank
    private String schemaName = "public";

    @NotBlank
    private String tableName = "vector_store";

    @Min(1)
    @Max(8192)
    private int dimensions = 384;

    @NotNull
    private DistanceMetric distanceMetric = DistanceMetric.COSINE;

    @NotNull
    private PgVectorIndexType indexType = PgVectorIndexType.HNSW;

    private boolean initializeSchema = false;

    private boolean removeExistingVectorStoreTable = false;

    @Min(1)
    private int maxDocumentBatchSize = 10_000;

    /**
     * 向量库装配开关：默认 true（fail-fast）。
     * 测试环境（application-test.yml）显式关闭，避免历史 @SpringBootTest 上下文
     * 因无 PG 凭据而整体启动失败；真实向量验收走独立集成测试（真连 PG）。
     */
    private boolean enabled = true;

    public void requireValid() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("pgvector 连接 URL 不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("pgvector 用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "pgvector 密码为空：必须通过环境变量 PG_VECTOR_PASSWORD 或启动参数注入，"
                            + "禁止在配置文件中保存真实口令");
        }
        if (dimensions < 1 || dimensions > 8192) {
            throw new IllegalArgumentException("pgvector 维度必须在 1-8192");
        }
        if (distanceMetric == null || indexType == null) {
            throw new IllegalArgumentException("pgvector 距离度量与索引类型不能为空");
        }
        if (schemaName == null || !SAFE_IDENTIFIER.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("schemaName 只允许字母、数字、下划线，且字母或下划线开头");
        }
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 只允许字母、数字、下划线，且字母或下划线开头");
        }
        if (initializeSchema) {
            throw new IllegalArgumentException(
                    "initialize-schema 必须为 false：表结构由外部幂等 DDL 管理，禁止应用启动时自动建表");
        }
        if (removeExistingVectorStoreTable) {
            throw new IllegalArgumentException(
                    "remove-existing-vector-store-table 必须为 false：禁止应用启动自动删除已有向量数据");
        }
    }
}
