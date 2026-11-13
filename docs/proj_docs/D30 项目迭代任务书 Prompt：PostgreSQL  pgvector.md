# D30 项目迭代任务书 Prompt：PostgreSQL / pgvector

## 一、角色与任务

你是一名资深 Java 后端工程师、Spring AI 工程师和 PostgreSQL/pgvector 数据库工程师。

请基于用户本地真实项目代码，完成 M3-Java-ds 项目的 D30 迭代：

**Day 30：PostgreSQL / pgvector 向量数据库环境与 Spring AI 接入基础。**

本次不是重新设计整个 RAG 系统，也不是直接实现完整 RAG 业务，而是为后续向量存储、向量检索和 RAG 链路建立稳定、可验证的 PostgreSQL/pgvector 基础设施。

必须先扫描真实项目，再制定实施方案。不得仅凭本任务书假设代码结构、依赖、数据库状态或已有功能。

核心原则：

1. 以本地仓库实际代码为准。
2. 优先沿用已有架构和技术决策，不重复实现已有功能。
3. PostgreSQL + pgvector 作为本项目唯一向量数据库，除非仓库已有经过验证的其他决策；如有，先分析并说明是否需要沿用或调整，不得擅自并存多个向量库。
4. 数据库环境、向量扩展、维度、索引和 Spring AI 配置必须相互一致。
5. 真实数据库验证失败时，必须如实报告失败，不得将 Mock、单元测试或编译成功描述为真实向量检索成功。

---

## 二、项目路径与技术基线

### 2.1 本地项目路径

项目根目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D30`

本次扫描与基础骨架目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D30\D30_postgresqlAndpgvector`

首先扫描项目根目录及其下的 `pom.xml`，确认 Maven 工程结构、父子模块关系、实际启动模块和依赖管理方式。

技术文档目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\proj_docs`

证据目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence`

临时文件目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs`

日志目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\logs`

### 2.2 应用技术基线

以下版本必须严格遵守：

| 组件                  | 版本或要求          |
| --------------------- | ------------------- |
| JDK                   | 21                  |
| Spring Boot           | 3.5.16              |
| Spring AI             | 1.1.8               |
| Maven                 | 3.9.16              |
| 应用运行环境          | Windows 11 本机     |
| 数据库                | PostgreSQL 18.7     |
| pgvector              | 0.8.7               |
| Embedding 维度        | 384                 |
| 距离度量              | COSINE              |
| PostgreSQL 向量运算符 | `<=>`               |
| 向量索引              | HNSW                |
| HNSW operator class   | `vector_cosine_ops` |

不得无故升级 Spring Boot、Spring AI、JDK 或其他基础依赖。

如发现实际 POM 与上述基线不一致，先列出差异并分析原因，不得直接覆盖版本。

### 2.3 虚拟机与 Docker 环境

数据库部署在 VMware NAT 网络中的 CentOS 7.9 虚拟机。

| 项目             | 已验证信息               |
| ---------------- | ------------------------ |
| 虚拟机 IP        | `192.168.200.153`        |
| 操作系统         | CentOS Linux 7.9.2009    |
| 账户/密码        | root/itcast              |
| Docker Engine    | 24.0.9                   |
| Docker Compose   | v2.24.7                  |
| Compose 命令     | `docker compose`         |
| PostgreSQL 容器  | `pgvector-pg18`          |
| 镜像             | `pgvector/pgvector:pg18` |
| PostgreSQL 版本  | 18.7                     |
| pgvector 版本    | 0.8.7                    |
| 数据库           | `spring_ai`              |
| 数据库用户       | `postgres`               |
| 数据库密码       | Postgres@2026            |
| 数据库端口       | 5432                     |
| 时区             | Asia/Shanghai            |
| Compose 项目目录 | `/root/pgvector-stack/`  |

应用在 Windows 本机运行，通过 JDBC 访问虚拟机 PostgreSQL。

JDBC URL：

`jdbc:postgresql://192.168.200.153:5432/spring_ai`

### 2.4 数据库当前已验证状态

用户已经验证：

- PostgreSQL 容器 `pgvector-pg18` 已部署。
- PostgreSQL 18.7 正常运行。
- pgvector 0.8.7 已安装。
- `spring_ai` 数据库已启用 `vector` 扩展。
- 查询 `pg_extension` 可以看到 `vector | 0.8.7`。

注意：

上述信息属于用户提供的已验证环境事实，不代表本次迭代已经重新验证数据库连通性。

实际执行时仍应通过只读检查确认当前状态。

---

## 三、仓库扫描与现状分析

开始编码前，必须先扫描以下内容。

### 3.1 项目结构

1. 扫描 D30 根目录下所有 `pom.xml`。
2. 确认父子模块关系和 Maven 聚合方式。
3. 确认实际启动模块、Spring Boot 启动类及 Java 包路径。
4. 确认资源文件、配置文件、SQL 文件、测试目录和现有文档。
5. 检查 D30 基础骨架是否已经存在 PostgreSQL、pgvector 或 Spring AI VectorStore 相关实现。

禁止无差别读取整个 M3 仓库。

### 3.2 参考 D29 工程

D29 是当前 M3 项目的前序工程基线。

参考路径：

`D29/D29_EBD`

重点检查：

- Maven 依赖管理。
- Spring AI 1.1.8 的依赖配置。
- EmbeddingModel 接口与实现。
- 本地 Embedding 模型。
- Embedding 向量维度与校验逻辑。
- DocumentChunk 数据结构。
- ChunkEmbedding 相关对象。
- Knowledge 模块。
- Retrieval 模块。
- 数据库配置与数据访问层。
- 模型配置外部化方式。
- 异常处理、日志脱敏和 TraceId。
- 测试规范及现有技术文档。

已知 D29 有本地 384 维 `LocalHashNgramEmbeddingModel`，但它是确定性字符 n-gram 哈希向量模型，不是预训练神经网络 Embedding。

不能把它描述为具有成熟语义理解能力的真实神经网络模型。

D29 已有 Embedding 生成、维度校验等能力，但此前全景扫描未发现完整的 pgvector 持久化与向量相似度检索实现。

这一判断必须通过本次本地代码扫描重新核实。

### 3.3 检查已有向量数据库决策

搜索以下关键词及相关实现：

- `VectorStore`
- `PgVectorStore`
- `RedisVectorStore`
- `Milvus`
- `EmbeddingModel`
- `ChunkEmbedding`
- `vector_store`
- `pgvector`
- `vector_cosine_ops`
- `similaritySearch`
- `embedding`

判断仓库目前属于哪种状态：

A. 没有任何现成向量存储。

B. 已有向量存储设计，但没有真实实现。

C. 已有其他向量数据库的真实实现。

D. 已有 PostgreSQL/pgvector 实现。

必须给出代码文件、类名、配置或 SQL 作为依据。

如果已经存在可用的向量存储，优先复核后沿用，不得为了完成 D30 而重复建设。

如果没有现成向量存储，则采用 PostgreSQL + pgvector 作为唯一向量数据库。

---

## 四、本次核心功能需求

### 4.1 PostgreSQL/pgvector 接入

建立 Spring Boot 应用访问 PostgreSQL 的基础能力。

要求：

1. 使用 PostgreSQL JDBC 驱动。
2. 使用环境变量或启动参数提供数据库连接信息。
3. 确认 Windows 本机可以访问虚拟机 `192.168.200.153:5432`。
4. 检查数据库连接池配置是否合理。
5. 明确连接失败时的错误提示。
7. 不得在数据库不可用时让应用虚假报告向量存储可用。

建议配置项：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

配置方式必须与当前 Spring Boot 工程保持一致。

不要直接复制本任务书中的配置覆盖已有配置文件。

### 4.2 pgvector 扩展检查

必须检查：

```sql
SELECT version();

SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

如果 `vector` 扩展尚未启用，执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

随后再次查询扩展版本。

扩展已经存在时，不要重复执行无意义的安装操作。

本次还需要确认 Spring AI PgVectorStore 对其他数据库扩展的实际依赖。

如果当前 Spring AI 1.1.8 的 PgVectorStore 需要 `hstore`、`uuid-ossp` 等扩展，先检查实际环境和初始化脚本，再决定是否启用。

不得假设只启用 `vector` 就必然满足所有 PgVectorStore 初始化要求。

### 4.3 向量维度规范

当前 Embedding 维度：

**384**

所有向量表必须明确使用：

```sql
vector(384)
```

禁止使用不带维度约束的向量列替代本次明确要求的 384 维约束。

后续可能使用的其他维度：

- 1536
- 1024
- 768

这些仅为未来可能的模型维度，不属于 D30 本次实现范围。

本次必须做到：

1. EmbeddingModel 实际输出维度为 384。
2. Spring AI PgVectorStore 配置维度为 384。
3. PostgreSQL 向量列维度为 384。
4. 写入向量前校验实际维度。
5. 查询向量前校验实际维度。
6. 数据库表结构与模型配置不一致时，明确报错。
7. 不允许通过截断、补零或静默转换掩盖维度不匹配。

建议通过以下 SQL 验证：

```sql
SELECT
    table_schema,
    table_name,
    column_name,
    data_type,
    udt_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'vector_store';
```

同时通过 `pg_attribute`、`format_type` 或其他可靠方式确认实际列类型是否为 `vector(384)`。

### 4.4 向量表与索引

优先采用 Spring AI 1.1.8 提供的 PgVectorStore 实现。

必须先核对当前版本依赖、自动配置和初始化机制，再决定采用自动初始化还是显式 SQL 初始化。

建议使用独立向量表，默认表名：

`vector_store`

向量列：

`embedding vector(384)`

索引要求：

```sql
CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
ON public.vector_store
USING hnsw (embedding vector_cosine_ops);
```

如果 Spring AI PgVectorStore 自动创建了索引，先检查实际索引定义，不得重复创建或造成索引名称冲突。

要求确认：

- 表结构。
- 主键类型。
- content 字段。
- metadata 字段。
- embedding 字段。
- 向量维度。
- HNSW 索引。
- operator class 是否为 `vector_cosine_ops`。

严禁使用 `remove-existing-vector-store-table=true` 等可能删除现有向量表的配置。

禁止在应用启动时自动删除已有向量数据。

如需创建或修改表结构，必须先检查当前表是否存在、数据是否需要保留，并提供可审查的 SQL。

### 4.5 Spring AI PgVectorStore 集成

必须严格基于 Spring AI 1.1.8。

优先核实并使用与该版本匹配的官方 Starter：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

以上依赖仅为预期集成方向，实际添加前必须检查：

1. 当前项目是否已经导入 Spring AI BOM。
2. 当前版本是否提供该 Starter。
3. 是否与当前 Spring Boot 3.5.16 兼容。
4. 是否已经存在 PostgreSQL JDBC 依赖。
5. 是否存在重复依赖。
6. 是否需要调整自动配置。

配置重点：

- 数据源连接。
- PgVectorStore dimensions。
- COSINE_DISTANCE。
- HNSW。
- schema-name。
- table-name。
- initialize-schema。
- schema-validation。

建议配置目标：

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        dimensions: 384
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        schema-name: public
        table-name: vector_store
        initialize-schema: false
        remove-existing-vector-store-table: false
```

注意：

- `initialize-schema` 是否开启必须根据实际 DDL 管理策略决定。
- 不得在不清楚现有表结构的情况下启用自动初始化。
- `remove-existing-vector-store-table` 必须保持关闭。
- 如果采用手动 DDL，必须提供明确的初始化 SQL 和执行说明。
- 不得同时通过手动 SQL 和自动初始化创建互相冲突的结构。

如果当前工程已经有统一的 EmbeddingModel Bean，优先复用，不要重复创建模型实例。

如果 D29 本地 EmbeddingModel 可以直接复用，则优先接入现有实现。

不得为了验证 pgvector 而绕开现有 EmbeddingModel 架构，额外创建一套重复的向量生成逻辑。

### 4.6 向量写入与相似度查询验证

必须实现最小可运行的向量写入和查询验证。

验证流程：

1. 获取现有 EmbeddingModel。
2. 对测试文本生成 384 维向量。
3. 检查向量非空。
4. 检查向量维度为 384。
5. 检查向量数值不存在 NaN 或 Infinity。
6. 将测试文档写入 PgVectorStore。
7. 使用查询文本生成查询向量。
8. 执行相似度查询。
9. 确认数据库确实返回匹配记录。
10. 检查结果中的文档内容和必要元数据。

测试文本应使用可读、可解释的中文或英文内容，不要使用随机数据掩盖问题。

建议至少准备 3 条测试文档，并使用其中一条内容相近的查询进行检索验证。

必须明确区分：

- 向量生成成功。
- 向量数据库写入成功。
- 向量数据库查询成功。
- 检索结果符合预期。

四者不能混为一谈。

### 4.7 SQL 直接验证

必须提供可以独立执行的 SQL 验证脚本。

至少包含：

**1. 扩展检查与启用**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

**2. 创建测试向量表**

```sql
CREATE TABLE IF NOT EXISTS d30_vector_smoke_test (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL
);
```

**3. 插入测试向量**

使用合法的 384 维向量。

不得使用省略号、占位符或不完整数组作为可执行 SQL。

**4. 相似度查询**

```sql
SELECT
    id,
    content,
    embedding <=> embedding AS cosine_distance
FROM d30_vector_smoke_test
ORDER BY embedding <=> embedding
LIMIT 5;
```

正式测试时必须使用实际查询向量，而不是简单地使用表中向量与自身比较作为唯一检索验证。

**5. HNSW 索引**

```sql
CREATE INDEX IF NOT EXISTS d30_vector_smoke_test_hnsw_idx
ON d30_vector_smoke_test
USING hnsw (embedding vector_cosine_ops);
```

注意：

- 测试表与 Spring AI 正式向量表应明确区分。
- 如果测试数据写入独立测试表，不得将其误认为 PgVectorStore 已完成写入。
- 如果使用 Spring AI 正式表进行验证，应确认写入与查询均由 PgVectorStore 完成。
- 不得误删已有业务表或历史向量数据。

### 4.8 Docker Compose 环境检查

虚拟机现有 Compose 项目目录：

`/root/pgvector-stack/`

检查命令：

```bash
cd /root/pgvector-stack && docker compose ps
```

查看指定容器：

```bash
docker inspect pgvector-pg18
```

查看数据库日志：

```bash
docker logs --tail 100 pgvector-pg18
```

检查 PostgreSQL：

```bash
docker exec -it pgvector-pg18 psql -U postgres -d spring_ai
```

检查扩展：

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

要求：

1. 检查现有容器状态。
2. 检查健康状态。
3. 检查 PostgreSQL 是否能够接受连接。
4. 检查端口映射。
5. 检查数据卷挂载。
6. 检查 PostgreSQL 日志。
7. 确认数据库可以正常访问。

如果容器没有健康检查配置，先说明现状，再决定是否有必要调整。

本次默认不修改现有 Compose 文件。

只有在明确发现必须修复且经过用户允许时，才允许修改：

`/root/pgvector-stack/docker-compose.yml`

不得擅自修改端口映射和数据卷挂载。

---

## 五、Docker 与数据库操作红线

以下约束优先级最高。

### 5.1 禁止操作

1. 不得删除虚拟机现有的 14 个历史停止容器。
2. 不得删除现有的 18 个旧镜像。
3. 不得执行 `docker system prune`。
4. 不得执行 `docker volume prune`。
5. 不得执行 `docker image prune`。
6. 不得执行 `docker container prune`。
7. 不得删除或重建现有 PostgreSQL 数据卷。
8. 不得擅自修改 `/root/pgvector-stack/docker-compose.yml` 的端口映射。
9. 不得擅自修改数据卷挂载。
10. 不得使用 `latest` 标签锁定镜像。
11. 不得在未确认数据安全的情况下执行 `docker compose down`。
12. 不得执行 `DROP DATABASE`、`DROP EXTENSION` 或清空业务表等破坏性操作。
13. 不得擅自重建现有 PostgreSQL 容器。

### 5.2 操作原则

优先只读检查。

确实需要执行数据库操作时：

1. 说明执行目的。
2. 说明影响范围。
3. 先检查对象是否存在。
4. 使用幂等 SQL。
5. 不修改无关对象。
6. 保留已有数据。
7. 保存执行结果。

如果数据库连接失败：

- 不要反复执行破坏性操作。
- 不要擅自修改 Docker 配置。
- 先检查 Windows 到虚拟机的网络连通性。
- 检查端口和容器状态。
- 检查 PostgreSQL 日志。
- 给出明确的故障原因或待确认项。

数据库不可用时，停止真实向量存储与检索验收，不得通过 Mock 伪造成功结果。

---

## 六、配置与安全要求

### 6.1 敏感信息

以下信息禁止硬编码：

- Embedding API Key。
- 其他第三方服务密钥。

必须通过环境变量或启动参数注入。

不要将真实密钥写入：

- Markdown 文档
- Git 提交

如需要提供本地配置示例，使用占位符。

### 6.2 日志脱敏

检查是否存在以下泄露风险：

- 数据库密码。
- API Key。
- 完整 JDBC 连接敏感参数。
- Embedding 原始请求。
- 完整向量数组。
- 大段原始文档内容。

日志只保留必要的状态、耗时、TraceId、错误分类和可安全记录的元数据。

### 6.3 环境配置隔离

开发环境和测试环境必须区分。

单元测试不得因为宿主机存在真实环境变量，就意外访问真实数据库或调用真实外部模型。

需要真实数据库验证时，使用明确的集成测试配置和启动方式。

---

## 七、代码实现范围

根据实际扫描结果决定具体文件，不得机械照搬以下目录。

预期涉及：

- Maven POM。
- PostgreSQL 数据源配置。
- Spring AI PgVectorStore 配置。
- EmbeddingModel 复用或适配。
- 向量存储服务。
- 数据库初始化 SQL。
- 向量维度校验。
- 数据库健康检查。
- 向量写入与查询测试。
- 配置示例。
- 技术文档。

实现应尽量保持职责清晰。

建议职责边界：

1. 数据源：负责 PostgreSQL 连接。
2. EmbeddingModel：负责文本向量生成。
3. PgVectorStore：负责向量持久化与相似度查询。
4. 向量校验：负责维度和数值合法性。
5. 集成测试：负责验证真实数据库写入与检索。

如果仓库已经有对应抽象，优先复用，不重复创建。

不要为了 D30 引入无关的 WebFlux、Agent、MCP、Tool Calling 或其他 RAG 功能。

不要提前实现 D31 及后续任务的业务需求。

---

## 八、测试与验收标准

### 8.1 Maven 编译

使用项目指定 Maven 版本和 JDK 21。

执行适合当前工程结构的 Maven 命令。

至少完成：

- Maven 依赖解析。
- 编译。
- 测试。
- 打包。

如果存在多个 Maven 模块，说明实际执行的模块范围。

不得仅凭 IDE 没有红线就报告编译成功。

### 8.2 单元测试

至少覆盖：

- Embedding 维度校验。
- 空向量校验。
- NaN / Infinity 校验。
- 维度不匹配异常。
- 配置绑定或相关校验逻辑。
- 数据库不可用时的异常处理。

单元测试不应依赖真实数据库。

### 8.3 数据库集成测试

必须尽可能完成以下真实验证：

| 验收项          | 验收要求                         |
| --------------- | -------------------------------- |
| PostgreSQL 连接 | Windows 本机 JDBC 连接成功       |
| 数据库版本      | PostgreSQL 18.7                  |
| pgvector 扩展   | 查询到 vector 0.8.7              |
| 表结构          | 存在正确的向量列                 |
| 向量维度        | vector(384)                      |
| 向量生成        | 实际 EmbeddingModel 输出 384 维  |
| 向量写入        | PgVectorStore 写入成功           |
| 向量查询        | PgVectorStore 返回真实数据库结果 |
| 距离度量        | COSINE_DISTANCE                  |
| 索引            | HNSW                             |
| Operator Class  | vector_cosine_ops                |

如果任何一项失败，必须单独记录失败原因。

### 8.4 健康检查与失败场景

必须验证或明确说明：

- 数据库正常时的健康状态。
- 数据库不可用时的健康状态。
- 数据库连接失败时的异常表现。
- pgvector 扩展缺失时的错误表现。
- 向量维度不匹配时的错误表现。

不得把应用进程启动成功等同于数据库和向量存储正常。

### 8.5 验收分级

最终必须按以下等级汇报：

**Level 1：工程编译通过**

只代表依赖解析、编译和打包成功。

**Level 2：数据库连接通过**

只代表应用能够访问 PostgreSQL。

**Level 3：pgvector 环境通过**

代表扩展、表结构、维度及索引检查通过。

**Level 4：真实向量存储通过**

代表实际 Embedding 向量已写入 PostgreSQL。

**Level 5：真实向量检索通过**

代表应用通过 PgVectorStore 执行真实相似度查询，并取得合理结果。

只有完成 Level 5，才可以报告 D30 的真实向量存储和检索链路通过。

不得使用较低等级的结果冒充较高等级。

---

## 九、技术文档与证据交付

### 9.1 技术文档

输出到：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\proj_docs`

必须生成两份 Markdown 文档。

**文档一：D30-PostgreSQL-pgvector项目迭代实施记录.md**

记录本次迭代的全量信息：

- 需求背景。
- 仓库现状。
- 技术选型与决策。
- 依赖调整。
- 代码结构。
- 配置说明。
- SQL。
- Docker 检查操作。
- 数据库连接过程。
- 向量表与索引。
- 向量写入和查询。
- 测试结果。
- 故障处理。
- Git 提交信息。
- 未完成事项。

**文档二：D30-PostgreSQL-pgvector技术原理与实践指南.md**

重点说明：

- PostgreSQL 与 pgvector 的关系。
- 向量数据类型。
- vector(N) 维度约束。
- cosine distance。
- `<=>` 运算符。
- HNSW 索引原理。
- `vector_cosine_ops`。
- Spring AI PgVectorStore。
- EmbeddingModel 与 VectorStore 的职责。
- JDBC 数据源配置。
- SQL 验证方法。
- Java 集成测试。
- 常见故障与排查。
- 生产环境注意事项。

文档内容必须与实际代码和测试结果一致。

不得提前编造测试数据、测试结果或运行日志。

### 9.2 证据目录

输出到：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence`

建议结构：

```text
docs/evidence/D30/
├── 01-project-scan.md
├── 02-maven-build.log
├── 03-test-result.log
├── 04-postgresql-connection.txt
├── 05-pgvector-extension.txt
├── 06-vector-schema.txt
├── 07-vector-index.txt
├── 08-vector-insert-query.txt
├── 09-health-check.txt
└── 10-git-commit.txt
```

只保存实际产生的证据。

未执行的验证不得创建伪造的成功结果文件。

### 9.3 临时文件

所有临时脚本、SQL、文本文件统一放入：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs`

**禁止自动删除临时文件。**

包括：

- 临时 SQL。
- PowerShell 脚本。
- 数据库检查脚本。
- 临时测试数据。
- 调试文本。
- 临时日志。

由用户自行决定是否删除。

### 9.4 日志

日志存放目录：

`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\logs`

按 D30 建立独立目录，不覆盖历史日志。

---

## 十、Git 管理要求

1. 开始前检查当前分支。
2. 检查工作区是否干净。
3. 检查最近提交。
4. 确认 D30 当前基线。
5. 只提交本次 D30 相关修改。
6. 不提交数据库密码和 API Key。
7. 不提交无关临时文件。
8. 不覆盖用户已有未提交修改。
9. 不执行 `git reset --hard`。
10. 不执行强制覆盖或强制推送。
11. 未经明确授权不得执行 push。

建议提交信息：

`feat(D30): integrate PostgreSQL pgvector vector store`

如果本次只完成部分能力，应使用与实际交付一致的提交说明。

---

## 十一、执行流程

必须按以下顺序执行，不得跳过前置检查。

### Phase 1：仓库扫描

- 扫描 D30 POM。
- 确认 Maven 模块。
- 检查 D30 基础骨架。
- 对照 D29 EmbeddingModel。
- 检查是否已有 VectorStore。
- 检查配置与数据库依赖。
- 输出现状报告。

### Phase 2：技术方案

明确：

- 是否采用 PostgreSQL + pgvector。
- 是否已有其他向量存储决策。
- 是否复用 D29 EmbeddingModel。
- 是否使用 Spring AI PgVectorStore。
- 依赖与配置调整。
- 表结构与索引方案。
- SQL 初始化方式。
- 测试策略。

发现会影响已有数据或现有架构的变更时，先停止并向用户说明。

### Phase 3：环境检查

- 检查虚拟机网络连通性。
- 检查 Docker 容器状态。
- 检查 PostgreSQL 状态。
- 检查扩展版本。
- 检查数据库连接。
- 检查向量维度。

不得擅自重建 Docker 环境。

### Phase 4：编码实现

- 按确认方案调整 POM。
- 完成数据库配置。
- 完成 PgVectorStore 接入。
- 复用 EmbeddingModel。
- 完成维度校验。
- 完成 SQL。
- 完成测试。

### Phase 5：验证

按 Level 1 至 Level 5 逐级验证。

记录真实命令、退出码和结果。

如果数据库无法访问，停止真实数据库集成验收，保留已完成的单元测试和编译证据，并说明阻塞原因。

### Phase 6：文档与证据

- 生成两份技术文档。
- 保存证据。
- 保存临时文件。
- 保存日志。
- 检查敏感信息。

### Phase 7：最终审查

检查：

- 是否引入重复依赖。
- 是否破坏 D30 原有功能。
- 是否误改 D29 历史快照。
- 是否存在硬编码密码。
- 是否存在向量维度不一致。
- 是否存在错误索引。
- 是否有自动删除表的配置。
- 是否存在虚假测试结论。
- 是否遗漏技术文档和证据。

---

## 十二、Token 与执行效率要求

本次任务采用精确扫描，避免无意义消耗上下文。

1. 不要读取整个 M3-Java-ds 仓库。
2. 优先扫描 D30 和 D29 相关文件。
3. 只读取与本次功能直接相关的代码。
4. 先列文件清单，再按需读取。
5. 不要重复输出完整文件内容。
6. 不要重复粘贴本任务书。
7. 测试日志优先保存到文件，只向上下文输出关键摘要。
8. 编译失败时只提取关键错误及必要上下文。
9. 不要为了增加测试数量重复编写等价测试。
10. 不得为了节省 Token 而跳过必要的代码检查和真实验证。

---

## 十三、最终交付报告

完成后必须按以下格式汇报：

### 1. 项目扫描结果

- 实际项目结构。
- Maven 模块。
- D30 基础骨架状态。
- D29 可复用能力。
- 已有向量存储决策。
- 本次采用的技术方案。

### 2. 代码修改清单

列出实际新增、修改的文件及用途。

### 3. 数据库验证

分别汇报：

- Docker 容器状态。
- PostgreSQL 连接。
- pgvector 扩展。
- vector(384) 表结构。
- HNSW 索引。
- 向量写入。
- 相似度查询。

### 4. 测试结果

明确区分：

- 编译。
- 单元测试。
- 集成测试。
- 真实数据库测试。

列出测试总数、失败数、错误数、跳过数及实际执行命令。

### 5. 文档与证据

列出两份技术文档、证据目录、临时文件和日志的实际路径。

### 6. Git 信息

- 分支。
- 修改文件数量。
- Commit ID。
- Commit Message。
- 工作区状态。

### 7. 未完成事项

列出所有未完成、失败、受阻和需要用户手动操作的内容。

### 8. 最终验收等级

明确标注达到 Level 1、Level 2、Level 3、Level 4 或 Level 5。

不得夸大完成度。

---

## 十四、最终执行原则

本次迭代的最终目标是：

**在现有 M3 Java AI 工程中，以 Spring AI 1.1.8 接入 PostgreSQL/pgvector，使用本地 384 维 EmbeddingModel，实现可验证的向量持久化与 cosine 相似度检索基础。**

必须遵循：

- 先扫描，再设计，再编码。
- 先确认环境，再操作数据库。
- 先检查现有结构，再执行 DDL。
- 复用已有 EmbeddingModel。
- PostgreSQL + pgvector 是唯一向量数据库。
- 维度必须统一为 384。
- 距离度量使用 COSINE。
- 索引使用 HNSW。
- 不破坏现有数据。
- 不泄露敏感配置。
- 不伪造测试结果。
- 不自动删除临时文件。
- 真实验证失败必须如实报告。

现在开始执行 Phase 1：仓库扫描。

**先输出扫描结果和技术方案，再进入代码修改。**