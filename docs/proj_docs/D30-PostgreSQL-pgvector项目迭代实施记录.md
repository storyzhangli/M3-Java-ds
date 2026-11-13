# D30-PostgreSQL-pgvector项目迭代实施记录

> 项目：M3-Java-ds（Spring AI 1.1.8 / Spring Boot 3.5.16 / JDK 21）
> 迭代：Day 30 —— PostgreSQL/pgvector 向量数据库接入
> 状态：**已完成（L1–L5 全部真实通过）**
> 生成时间：2026-11-13

---

## 第一章 任务概述与执行过程

### 1.1 迭代目标

在 D30 骨架（D29 全量能力快照）之上，以 Spring AI 1.1.8 接入 **PostgreSQL + pgvector**
（虚拟机 192.168.200.153 容器部署）作为**唯一向量库**，复用 D29 已有的 384 维本地
`LocalHashNgramEmbeddingModel`（确定性字符/词 n-gram 哈希模型，**非预训练神经网络**），
实现可验证的向量持久化与 COSINE 相似度检索。

### 1.2 执行过程时间线

| 阶段 | 内容 | 结果 |
|---|---|---|
| Phase 1 | 仓库扫描（POM/Embedding 组件/Git） | 判定现状 A：无现成向量存储 |
| Phase 2 | 技术方案 | 手动装配 + 双数据源隔离（见第三章） |
| Phase 3 | 环境检查（SSH 只读） | 容器 healthy、PG 18.7、pgvector 0.8.7、vector_store 表 vector(384)+HNSW cosine 已就绪 |
| Phase 4 | 编码 + 单元测试 | 7 主类 + 5 测试类；历史测试回归已修复 |
| Phase 5 | 分级验收 | L1 编译 / L2 连接 / L3 环境 / L4 真实写入 / L5 真实检索 **全部通过** |
| Phase 6 | 文档、证据、Git | 两份文档 + 8 份证据 + 日志，仅提交 D30 相关变更 |

### 1.3 验收分级结论

| Level | 含义 | 结果 | 证据 |
|---|---|---|---|
| L1 | 工程编译通过 | ✅ 编译 + 全量单测 BUILD SUCCESS | `docs/evidence/D30/04-compile-test.md` |
| L2 | 数据库连接通过 | ✅ 主应用启动 pgvector 连接池真连 + 集成测试真连 | `docs/evidence/D30/06-app-startup.md` |
| L3 | pgvector 环境通过 | ✅ 扩展 0.8.7 / 表 / vector(384) / HNSW cosine 索引 | `docs/evidence/D30/03-env-check.md`、`05` |
| L4 | 真实向量存储通过 | ✅ 5 条 384 维真实向量写入，COUNT 复核=5 | `docs/evidence/D30/05-integration-test.md` |
| L5 | 真实相似度检索通过 | ✅ COSINE 检索 top1 命中；自查询 score=1.0 | `docs/evidence/D30/05-integration-test.md` |

---

## 第二章 仓库扫描结论（Phase 1）

### 2.1 向量存储现状

Grep 全仓 `VectorStore|PgVectorStore|pgvector|vector_store|pgjdbc|postgresql` 仅命中两个
pom.xml（命中原因与 pgvector 无关）。**仓库没有任何现成 VectorStore / pgvector 实现**，
按任务书规则采用 PostgreSQL + pgvector 作为唯一向量库。

### 2.2 可复用资产

- `LocalHashNgramEmbeddingModel`（`knowledge/embedding`）：实现 Spring AI `EmbeddingModel`，
  确定性哈希 n-gram，384 维，输出 L2 归一化向量；
- `EmbeddingVectorValidator`：空向量 / 维度 / NaN-Infinity / 零向量校验；
- `EmbeddingModelConfiguration`：唯一 `EmbeddingModel` Bean；
- `EmbeddingProperties`（`app.embedding.*`）：dimensions=384、COSINE、LOCAL；
- `EmbeddingModelDescriptor` / `VectorSpaceIdentity` / `IndexCompatibilityValidator`：向量空间契约；
- 数据源现状：主数据源为 MySQL（chat/prompt 业务），PG 需以独立连接接入。

### 2.3 Spring AI 1.1.8 API 实测结论（javap 反查本地仓库 jar）

- `PgVectorStore` 仅 Builder 构造：`PgVectorStore.builder(JdbcTemplate, EmbeddingModel)`；
- 自动配置 `PgVectorStoreAutoConfiguration` 带 `@ConditionalOnMissingBean`，手动 Bean 可接管；
- `VectorStore` 接口无 `write(Document, float[])`，写入走 `add(List<Document>)`，
  由注入的同一 EmbeddingModel 生成向量（本模型确定性，与 D29 输出一致）；
- 检索 API：`SearchRequest.builder().query().topK().similarityThreshold()`。

---

## 第三章 技术方案

1. **依赖**：新增 `spring-ai-starter-vector-store-pgvector`（BOM 1.1.8 治理）与
   `org.postgresql:postgresql`（Boot 3.5.16 治理），仅此两个。
2. **双数据源隔离**：PG 连接（HikariDataSource + JdbcTemplate）不以 Spring Bean 暴露
   （否则 `@ConditionalOnSingleCandidate(DataSource.class)` 会让 JdbcTemplate/MyBatis
   自动配置整体退让，破坏 MySQL 业务），由配置类内部持有并在容器关闭时显式释放。
3. **手动构造 PgVectorStore Bean**（类型精确为 PgVectorStore），官方自动配置退让。
4. **装配守卫**：配置维度 ≠ EmbeddingModel 维度、或非 COSINE，均在触碰数据库前拒绝装配。
5. **DDL 策略**：`initialize-schema=false`、`remove-existing-vector-store-table=false`；
   幂等 DDL 脚本只作文档化参考，不自动执行。
6. **环境检查**：`PgVectorEnvironmentVerifier` 只读验证连通性/扩展/表/维度/索引，启动时输出报告。
7. **服务层**：`KnowledgeVectorStoreService` 写入（Chunk→Document+UUID id+溯源 metadata）
   与检索（topK/threshold→`VectorSearchHit`）。
8. **测试策略**：单元测试无 DB；真实集成测试由 `D30_PG_INTEGRATION=true` 门控、独立 smoke 表、
   只清理标记行；test profile 关闭向量库装配，保护历史 @SpringBootTest。
9. **凭据**：`PG_VECTOR_URL / PG_VECTOR_USERNAME / PG_VECTOR_PASSWORD` 环境变量注入。

---

## 第四章 修改文件清单（完整内容）

### 4.1 `D30_postgresqlAndpgvector/pom.xml`（修改）

在 Lombok 依赖前插入以下两个依赖：

```xml
        <!--
            D30 新增：PostgreSQL/pgvector 向量存储（唯一向量库）。
            - spring-ai-starter-vector-store-pgvector：PgVectorStore + 自动配置，
              版本由父 POM spring-ai-bom:1.1.8 统一治理，此处不写 <version>；
            - org.postgresql:postgresql：PG JDBC 驱动，版本由 Spring Boot 3.5.16
              dependencyManagement 统一治理。
            注意：主数据源仍为 MySQL（chat/prompt 业务），PG 仅作为向量库的
            独立数据源，由 PgVectorDataSourceConfiguration 显式构造。
        -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
```

其余依赖与插件均未改动。

### 4.2 `src/main/resources/application.yml`（修改）

在 `app.embedding` 配置段之后、`app.knowledge` 之前插入：

```yaml
  # --------------------------------------------------------------------------
  # D30 新增：PostgreSQL/pgvector 向量存储（唯一向量库，独立于 MySQL 主数据源）。
  # - 凭据只允许环境变量注入（PG_VECTOR_PASSWORD 等），禁止硬编码真实密码入库；
  # - initialize-schema 固定 false：表结构由外部幂等 DDL 管理（scripts 见 docs/temp_docs/D30）；
  # - remove-existing-vector-store-table 固定 false：禁止应用启动自动删除已有向量数据；
  # - 维度必须与 app.embedding.dimensions（384）一致，装配时由代码强校验。
  # --------------------------------------------------------------------------
  vectorstore:
    pgvector:
      url: ${PG_VECTOR_URL:jdbc:postgresql://192.168.200.153:5432/spring_ai}
      username: ${PG_VECTOR_USERNAME:postgres}
      password: ${PG_VECTOR_PASSWORD:}
      schema-name: ${PG_VECTOR_SCHEMA:public}
      table-name: ${PG_VECTOR_TABLE:vector_store}
      dimensions: ${PG_VECTOR_DIMENSIONS:384}
      distance-metric: ${PG_VECTOR_DISTANCE_METRIC:COSINE}
      index-type: ${PG_VECTOR_INDEX_TYPE:HNSW}
      initialize-schema: ${PG_VECTOR_INITIALIZE_SCHEMA:false}
      remove-existing-vector-store-table: ${PG_VECTOR_REMOVE_EXISTING:false}
      max-document-batch-size: ${PG_VECTOR_MAX_BATCH_SIZE:10000}
```

### 4.3 `src/test/resources/application-test.yml`（修改）

```yaml
app:
  ai:
    history:
      default-owner: ut-default
  # D30：历史 @SpringBootTest 测试环境不装配 pgvector 向量库（无 PG 凭据，避免上下文启动失败）。
  # 真实向量验收由 PgVectorStoreRealIntegrationTest 独立执行（D30_PG_INTEGRATION=true 门控，真连 PG）。
  vectorstore:
    pgvector:
      enabled: false
```

---

## 第五章 新增文件清单（完整内容）

### 5.1 `src/main/java/com/zl/d30/knowledge/vectorstore/PgVectorStoreProperties.java`

```java
package com.zl.d30.knowledge.vectorstore;

import com.zl.d30.knowledge.embedding.DistanceMetric;
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
 * D30 外部化 PostgreSQL/pgvector 向量存储配置。
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
```

### 5.2 `src/main/java/com/zl/d30/knowledge/vectorstore/PgVectorIndexType.java`

```java
package com.zl.d30.knowledge.vectorstore;

/**
 * pgvector 相似度索引类型（D30 仅实际支持 HNSW + vector_cosine_ops）。
 */
public enum PgVectorIndexType {
    NONE,
    IVFFLAT,
    HNSW
}
```

### 5.3 `src/main/java/com/zl/d30/knowledge/vectorstore/PgVectorEnvironmentReport.java`

```java
package com.zl.d30.knowledge.vectorstore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * pgvector 环境只读检查报告：连通性、扩展、向量表、维度与索引的真实状态。
 */
public record PgVectorEnvironmentReport(
        boolean reachable,
        String pgVersion,
        String vectorExtensionVersion,
        boolean tableExists,
        String tableQualifiedName,
        String embeddingColumnType,
        Integer embeddingColumnDimensions,
        List<String> indexDefinitions,
        List<String> problems,
        LocalDateTime checkedAt) {

    public boolean healthy() {
        return reachable && tableExists && problems.isEmpty();
    }
}
```

### 5.4 `src/main/java/com/zl/d30/knowledge/vectorstore/PgVectorEnvironmentVerifier.java`

```java
package com.zl.d30.knowledge.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pgvector 环境只读检查器：验证连接、扩展、向量表、embedding 维度与相似度索引。
 *
 * <p>全部 SQL 均为只读查询，不执行任何 DDL/DML；embedding 列维度与配置维度不一致时
 * 记录到 problems 而不是静默截断或补零。</p>
 */
public class PgVectorEnvironmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(PgVectorEnvironmentVerifier.class);

    /** 解析 format_type 输出的 vector(384) 形式。 */
    private static final Pattern VECTOR_TYPE = Pattern.compile("^vector\\((-?\\d+)\\)$");

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorStoreProperties properties;

    public PgVectorEnvironmentVerifier(JdbcTemplate jdbcTemplate, PgVectorStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public PgVectorEnvironmentReport verify() {
        List<String> problems = new ArrayList<>();
        LocalDateTime checkedAt = LocalDateTime.now();
        String qualified = qualifiedName();

        // 1. 连通性 + 版本（连接失败直接返回不可达报告，不做后续检查）
        String pgVersion;
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                problems.add("SELECT 1 连通性检查未返回预期结果");
            }
            pgVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        } catch (DataAccessException ex) {
            problems.add("PostgreSQL 连接失败: " + rootMessage(ex));
            log.warn("pgvector 环境检查：PostgreSQL 不可达，qualified={}", qualified);
            return new PgVectorEnvironmentReport(false, null, null, false, qualified,
                    null, null, List.of(), problems, checkedAt);
        }

        // 2. pgvector 扩展版本
        String extensionVersion = null;
        List<String> versions = jdbcTemplate.query(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                (rs, rowNum) -> rs.getString(1));
        if (versions.isEmpty()) {
            problems.add("pgvector 扩展未安装");
        } else {
            extensionVersion = versions.get(0);
        }

        // 3. 向量表是否存在
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                Boolean.class, properties.getSchemaName(), properties.getTableName());
        boolean exists = Boolean.TRUE.equals(tableExists);
        if (!exists) {
            problems.add("向量表不存在: " + qualified);
        }

        // 4. embedding 列类型与维度
        String columnType = null;
        Integer columnDimensions = null;
        List<String> indexDefs = List.of();
        if (exists) {
            List<String> types = jdbcTemplate.query(
                    "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                            + "WHERE a.attrelid = ?::regclass AND a.attname = 'embedding' "
                            + "AND a.attnum > 0 AND NOT a.attisdropped",
                    (rs, rowNum) -> rs.getString(1), qualified);
            if (types.isEmpty()) {
                problems.add("向量表缺少 embedding 列: " + qualified);
            } else {
                columnType = types.get(0);
                columnDimensions = parseVectorDimensions(columnType);
                if (columnDimensions == null) {
                    problems.add("embedding 列类型不是 vector(n): " + columnType);
                } else if (columnDimensions != properties.getDimensions()) {
                    problems.add("embedding 列维度与配置不一致: 列=" + columnDimensions
                            + "，配置=" + properties.getDimensions());
                }
            }

            indexDefs = jdbcTemplate.query(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY indexname",
                    (rs, rowNum) -> rs.getString(1),
                    properties.getSchemaName(), properties.getTableName());
            boolean hasCosineHnsw = indexDefs.stream()
                    .map(def -> def.toLowerCase(Locale.ROOT))
                    .anyMatch(def -> def.contains("using hnsw") && def.contains("vector_cosine_ops"));
            if (!hasCosineHnsw) {
                problems.add("未发现 HNSW + vector_cosine_ops 相似度索引");
            }
        }

        PgVectorEnvironmentReport report = new PgVectorEnvironmentReport(
                true, pgVersion, extensionVersion, exists, qualified,
                columnType, columnDimensions, indexDefs, problems, checkedAt);
        if (report.healthy()) {
            log.info("pgvector 环境检查通过: table={}, embedding={}, extension={}, pg={}",
                    qualified, columnType, extensionVersion, shortVersion(pgVersion));
        } else {
            log.warn("pgvector 环境检查发现问题: {}", problems);
        }
        return report;
    }

    private String qualifiedName() {
        return properties.getSchemaName() + "." + properties.getTableName();
    }

    private Integer parseVectorDimensions(String columnType) {
        if (columnType == null) {
            return null;
        }
        Matcher matcher = VECTOR_TYPE.matcher(columnType);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private String shortVersion(String pgVersion) {
        if (pgVersion == null) {
            return null;
        }
        int comma = pgVersion.indexOf(',');
        return (comma > 0 ? pgVersion.substring(0, comma) : pgVersion).strip();
    }
}
```

### 5.5 `src/main/java/com/zl/d30/knowledge/vectorstore/PgVectorStoreConfiguration.java`

```java
package com.zl.d30.knowledge.vectorstore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d30.common.AiConfigurationException;
import com.zl.d30.knowledge.embedding.DistanceMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D30 PostgreSQL/pgvector 向量存储装配（手动构造，双数据源隔离）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>PG 连接（HikariDataSource/JdbcTemplate）不以 Spring Bean 暴露：主数据源仍是 MySQL，
 *       新增第二个 DataSource Bean 会使 JdbcTemplateAutoConfiguration / MyBatis 自动配置
 *       因 {@code @ConditionalOnSingleCandidate(DataSource.class)} 整体退让，破坏现有业务；</li>
 *   <li>手动构造 PgVectorStore Bean（类型精确为 PgVectorStore），Spring AI 官方
 *       PgVectorStoreAutoConfiguration 因 {@code @ConditionalOnMissingBean} 自动退让，
 *       不会误用 MySQL JdbcTemplate 建向量表；</li>
 *   <li>装配前强制校验：配置维度必须与当前 EmbeddingModel（复用 D29
 *       LocalHashNgramEmbeddingModel，384 维）一致，距离度量仅允许 COSINE；</li>
 *   <li>initializeSchema / removeExistingVectorStoreTable 恒为 false，禁止应用启动
 *       建表或删除已有向量数据；</li>
 *   <li>配置类实现 DisposableBean，应用关闭时显式关闭 PG 连接池。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = true)
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreConfiguration.class);

    private HikariDataSource pgDataSource;
    private JdbcTemplate pgJdbcTemplate;

    @Bean
    public PgVectorStore pgVectorStore(PgVectorStoreProperties properties,
                                       EmbeddingModel embeddingModel) {
        properties.requireValid();
        if (properties.getDimensions() != embeddingModel.dimensions()) {
            throw new AiConfigurationException("pgvector 配置维度 " + properties.getDimensions()
                    + " 与当前 EmbeddingModel 维度 " + embeddingModel.dimensions()
                    + " 不一致，拒绝装配向量存储");
        }
        if (properties.getDistanceMetric() != DistanceMetric.COSINE) {
            throw new AiConfigurationException(
                    "D30 仅支持 COSINE 距离度量（vector_cosine_ops），当前配置: "
                            + properties.getDistanceMetric());
        }

        this.pgDataSource = createDataSource(properties);
        this.pgJdbcTemplate = new JdbcTemplate(this.pgDataSource);

        PgVectorStore store = PgVectorStore.builder(this.pgJdbcTemplate, embeddingModel)
                .schemaName(properties.getSchemaName())
                .vectorTableName(properties.getTableName())
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(properties.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(mapIndexType(properties.getIndexType()))
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .initializeSchema(properties.isInitializeSchema())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
        log.info("PgVectorStore 装配完成: table={}.{}, dimensions={}, distance=COSINE, index=HNSW, initializeSchema=false",
                properties.getSchemaName(), properties.getTableName(), properties.getDimensions());
        return store;
    }

    @Bean
    public PgVectorEnvironmentVerifier pgVectorEnvironmentVerifier(PgVectorStore pgVectorStore,
                                                                   PgVectorStoreProperties properties) {
        return new PgVectorEnvironmentVerifier(this.pgJdbcTemplate, properties);
    }

    /**
     * 启动完成后输出一次只读环境报告（连接失败时 Hikari fail-fast 已阻止启动，此处只记录详情）。
     */
    @Bean
    public ApplicationRunner pgVectorEnvironmentReportRunner(PgVectorEnvironmentVerifier verifier) {
        return args -> {
            PgVectorEnvironmentReport report = verifier.verify();
            if (report.healthy()) {
                log.info("pgvector 环境报告: healthy=true, table={}, embedding={}, extension={}, pg={}",
                        report.tableQualifiedName(), report.embeddingColumnType(),
                        report.vectorExtensionVersion(), report.pgVersion());
            } else {
                log.warn("pgvector 环境报告: healthy=false, problems={}", report.problems());
            }
        };
    }

    private HikariDataSource createDataSource(PgVectorStoreProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setPoolName("pgvector-pool");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        // fail-fast：连接池初始化 1 秒内拿不到连接则启动失败，不掩盖数据库不可用
        config.setInitializationFailTimeout(1_000);
        return new HikariDataSource(config);
    }

    private PgVectorStore.PgIndexType mapIndexType(PgVectorIndexType indexType) {
        return switch (indexType) {
            case NONE -> PgVectorStore.PgIndexType.NONE;
            case IVFFLAT -> PgVectorStore.PgIndexType.IVFFLAT;
            case HNSW -> PgVectorStore.PgIndexType.HNSW;
        };
    }

    @Override
    public void destroy() {
        if (this.pgDataSource != null) {
            this.pgDataSource.close();
            log.info("pgvector 连接池已关闭");
        }
    }
}
```

### 5.6 `src/main/java/com/zl/d30/knowledge/vectorstore/KnowledgeVectorStoreService.java`

```java
package com.zl.d30.knowledge.vectorstore;

import com.zl.d30.common.ResultCode;
import com.zl.d30.knowledge.chunking.DocumentChunk;
import com.zl.d30.knowledge.embedding.EmbeddingException;
import com.zl.d30.knowledge.embedding.EmbeddingModelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识 Chunk 向量持久化与 COSINE 相似度检索服务（D30）。
 *
 * <p>写入时把 D29 的 {@link DocumentChunk} 转换为 Spring AI Document（id 为 UUID，
 * 溯源信息全部进入 metadata），由注入的 PgVectorStore 使用同一
 * LocalHashNgramEmbeddingModel（确定性 384 维）生成向量后持久化；检索时按
 * COSINE 相似度返回 topK 命中。</p>
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStoreService.class);

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 50;

    private final VectorStore vectorStore;
    private final EmbeddingModelDescriptor descriptor;

    public KnowledgeVectorStoreService(VectorStore vectorStore,
                                       EmbeddingModelDescriptor descriptor) {
        this.vectorStore = vectorStore;
        this.descriptor = descriptor;
    }

    /**
     * 将 Chunk 列表持久化到 pgvector 向量库，返回实际写入条数。
     */
    public int storeChunks(List<DocumentChunk> chunks) {
        requireChunks(chunks);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            documents.add(toVectorDocument(chunk));
        }
        vectorStore.add(documents);
        log.info("向量库写入完成: chunks={}, vectorSpace={}, dimensions={}",
                documents.size(), descriptor.vectorSpaceId(), descriptor.dimensions());
        return documents.size();
    }

    /**
     * COSINE 相似度检索；topK 传 0 或负数时使用默认值，上限 50。
     */
    public List<VectorSearchHit> search(String query, int topK, Double similarityThreshold) {
        if (query == null || query.isBlank()) {
            throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID, "检索 Query 不能为空");
        }
        int effectiveTopK = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        double threshold = similarityThreshold == null
                ? SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL
                : similarityThreshold;
        SearchRequest request = SearchRequest.builder()
                .query(query.strip())
                .topK(effectiveTopK)
                .similarityThreshold(threshold)
                .build();
        List<Document> hits = vectorStore.similaritySearch(request);
        List<VectorSearchHit> result = hits.stream().map(VectorSearchHit::from).toList();
        log.info("向量检索完成: query=[{}], topK={}, hits={}",
                truncate(query.strip(), 40), effectiveTopK, result.size());
        return result;
    }

    private void requireChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "待写入向量库的 Chunk 列表不能为空");
        }
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                        "存在空 Chunk 或空内容，拒绝写入向量库");
            }
        }
    }

    private Document toVectorDocument(DocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.documentId());
        metadata.put("title", chunk.title());
        metadata.put("version", chunk.version());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("strategy", chunk.strategy() == null ? null : chunk.strategy().name());
        metadata.put("pageNumbers", chunk.pageNumbers());
        metadata.put("characterCount", chunk.characterCount());
        metadata.put("vectorSpaceId", descriptor.vectorSpaceId());
        metadata.put("modelName", descriptor.modelName());
        return new Document(UUID.randomUUID().toString(), chunk.content(), metadata);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
```

### 5.7 `src/main/java/com/zl/d30/knowledge/vectorstore/VectorSearchHit.java`

```java
package com.zl.d30.knowledge.vectorstore;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 向量库检索命中结果：剥离 Spring AI Document 内部结构，仅暴露溯源与评分字段。
 */
public record VectorSearchHit(
        String id,
        String documentId,
        Integer chunkIndex,
        String title,
        Double score,
        String contentSnippet) {

    private static final int DEFAULT_SNIPPET_LENGTH = 160;

    public static VectorSearchHit from(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new VectorSearchHit(
                document.getId(),
                asString(metadata.get("documentId")),
                asInteger(metadata.get("chunkIndex")),
                asString(metadata.get("title")),
                document.getScore(),
                snippet(document.getText(), DEFAULT_SNIPPET_LENGTH));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String snippet(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String compact = text.strip();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }
}
```

### 5.8 测试类（5 个，全部位于 `src/test/java/com/zl/d30/knowledge/vectorstore/`）

- `PgVectorStorePropertiesTest.java`：默认值契约 / URL 空拒绝 / initialize-schema 拒绝 /
  remove-existing 拒绝 / 危险表名拒绝（5 用例）；
- `PgVectorStoreConfigurationGuardTest.java`：维度 384 vs 512 拒绝 / 非 COSINE 拒绝（2 用例）；
- `PgVectorEnvironmentVerifierTest.java`：健康报告 / 维度不一致 / 扩展缺失 / 索引缺失 /
  连接失败不可达（5 用例）；
- `KnowledgeVectorStoreServiceTest.java`：Chunk→Document 溯源 / 空列表拒绝 / 检索映射 /
  空 Query 拒绝（4 用例）；
- `PgVectorStoreRealIntegrationTest.java`：环境健康 / 真实写入+检索 / COSINE 自查询 /
  PG 维度拒绝（4 用例，D30_PG_INTEGRATION=true 门控）。

### 5.9 `docs/temp_docs/D30/pgvector-schema-idempotent.sql`

```sql
-- D30 PostgreSQL/pgvector 幂等建表脚本（文档化参考，应用不自动执行）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS public.vector_store (
    id        uuid PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(384)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON public.vector_store USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS public.d30_vector_smoke_test (
    id        uuid PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(384)
);

CREATE INDEX IF NOT EXISTS d30_vector_smoke_hnsw_idx
    ON public.d30_vector_smoke_test USING hnsw (embedding vector_cosine_ops);
```

---

## 第六章 验证记录（全部真实执行）

| # | 命令（关键参数） | 结果 |
|---|---|---|
| 1 | `mvn compile`（Maven 3.9.16 / JDK 21） | 退出码 0 |
| 2 | `mvn test`（门控关闭） | BUILD SUCCESS；全仓 0 失败 0 错误；新增 16 单测通过；历史回归恢复 |
| 3 | `mvn test -Dtest=PgVectorStoreRealIntegrationTest`（D30_PG_INTEGRATION=true + 环境变量凭据） | 4/4 通过：环境 healthy、写入 5 行、top1 命中、自查询 score=1.0、维度拒绝生效 |
| 4 | `mvn spring-boot:run`（PG_VECTOR_PASSWORD 注入） | 启动成功：pgvector 连接池真连、环境报告 healthy、Tomcat 8080 就绪，随后正常停止 |
| 5 | SSH 只读复核 | 主表 0 行（无污染）、smoke 表已清理、索引完好 |

真实检索证据（集成测试 stdout）：

```
[D30-IT] 写入后行数: 5
[D30-IT] query=压缩机启动没有反应 -> top1 docId=doc-001, score=0.1462194323539734, top2 score=0.04456675052642822
[D30-IT] 同文本自查询 score=1.0
[D30-IT] 轴承检索 top1 score=0.2909855246543884, top2 score=0.0
[D30-IT] 3 维向量写入 vector(384) 列已被 PostgreSQL 拒绝
```

---

## 第七章 红线遵守情况

- ✅ 未删除任何容器/镜像/卷，未执行 prune；
- ✅ 未修改 docker-compose 端口与数据卷挂载，未重建 PG 容器；
- ✅ 未 DROP 数据库/扩展/业务表；未清空任何已有业务数据（主表复核 0 行，从未写入）；
- ✅ `remove-existing-vector-store-table=false`、`initialize-schema=false`；
- ✅ 未用 Mock 冒充真实检索（L4/L5 均为真连 PG 实测，输出见证据 05）；
- ✅ 凭据仅经环境变量注入，密码未写入代码/文档/日志/Git；
- ✅ 未截断/补零/静默转换维度（配置守卫 + PG 服务端拒绝双重防护）；
- ✅ 仅提交 D30 相关变更（见证据 08）。

## 第八章 遗留事项

1. 主业务接口（Controller）尚未调用 `KnowledgeVectorStoreService`——本次交付服务层与基础设施，
   由后续迭代按需接入（例如 D31 检索链路）；
2. 未来维度扩展（1536/1024/768）不在 D30 范围，向量空间标识机制（vectorSpaceId）已预留；
3. 用户既有实验表 `d30_vector_test`（3 维）未触碰，是否清理由用户决定。
