# M3 知识笔记：PostgreSQL / pgvector 环境与版本固定

> **学习阶段：** M3 - RAG 全链路  
> **核心主题：** 向量数据库选型、PostgreSQL 与 pgvector 原理、环境版本固定  
> **主实现：** PostgreSQL 18.7 + pgvector 0.8.7  
> **应用技术栈：** JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / Maven 3.9.16  
> **当前向量维度：** 384  
> **检索度量：** Cosine  
> **向量索引：** HNSW / vector_cosine_ops  
> **资料核验日期：** 2026-11-13

---

## 0. 当前项目环境基线

本节区分已核验事实和后续需要重新确认的事项。

### 0.1 已核验环境

| 项目            | 当前值                                           |
| --------------- | ------------------------------------------------ |
| 虚拟机操作系统  | CentOS Linux 7.9.2009                            |
| 虚拟机 IP       | 192.168.200.153                                  |
| 网络            | VMware NAT，仅 Windows 宿主机可达                |
| Docker Engine   | 24.0.9                                           |
| Docker Compose  | v2.24.7，使用 `docker compose`                   |
| PostgreSQL 容器 | pgvector-pg18                                    |
| 镜像            | pgvector/pgvector:pg18                           |
| PostgreSQL      | 18.7                                             |
| pgvector        | 0.8.7                                            |
| 数据库          | spring_ai                                        |
| 数据库用户      | postgres                                         |
| 数据库端口      | 5432                                             |
| JDBC URL        | jdbc:postgresql://192.168.200.153:5432/spring_ai |
| pgvector 扩展   | 已启用，版本 0.8.7                               |
| 数据卷          | pgvector-stack_pg18_data                         |
| 时区            | Asia/Shanghai                                    |
| Java            | JDK 21                                           |
| Spring Boot     | 3.5.16                                           |
| Spring AI       | 1.1.8                                            |
| Maven           | 3.9.16                                           |
| Embedding 维度  | 384                                              |
| 距离度量        | Cosine                                           |
| 索引            | HNSW / vector_cosine_ops                         |

### 0.2 必须遵守的环境边界

1. 不修改虚拟机上已有的 14 个历史停止容器和 18 个旧镜像。
2. 未经明确要求，不修改 `/root/pgvector-stack/docker-compose.yml` 的端口映射和数据卷挂载。
3. 不使用 `latest` 作为版本固定方案。
4. 数据库密码、API Key 不写入代码或 `application.yml`。
5. 所有向量列必须与实际 Embedding 维度一致，当前使用 `vector(384)`。
6. 不因学习需要重新安装 PostgreSQL 或覆盖现有数据卷。
7. 未真实执行的命令、SQL、编译和连接测试，不得记录为通过。

### 0.3 后续版本变更检查

以下项目必须分别核验，不能只看 Docker 镜像标签：

| 检查对象    | 核验内容                             |
| ----------- | ------------------------------------ |
| Docker 镜像 | 镜像名称、标签、镜像摘要             |
| PostgreSQL  | 实际运行的服务端版本                 |
| pgvector    | 扩展实际安装版本、数据库中启用的版本 |
| Java        | 实际运行的 JDK                       |
| pgJDBC      | Maven 最终解析的驱动版本             |
| Spring Boot | 项目实际依赖版本                     |
| Spring AI   | 项目实际依赖版本                     |
| Embedding   | 模型标识、版本、输出维度             |
| 索引        | 距离度量、操作符类和索引定义         |

**关键认识：** 镜像版本、数据库服务端版本、扩展版本和 Java 驱动版本是不同层次的信息，必须分别核验。

---

# 1. 技术定位与架构对比

## 1.1 什么是向量数据库？

向量数据库的核心任务是存储向量，并根据向量之间的距离或相似度查找最接近的记录。

例如设备故障知识库：

| 故障描述           | Embedding           |
| ------------------ | ------------------- |
| 设备启动后立即停机 | `[0.12, 0.35, ...]` |
| 电机启动失败       | `[0.15, 0.31, ...]` |
| 冷却液温度过高     | `[0.72, 0.19, ...]` |

用户提问：

> 设备无法正常启动，运行几秒后就停机。

系统将问题转换为查询向量，再与知识库中的向量进行相似度计算，找出语义上接近的知识片段。

向量数据库需要解决：

1. 向量存储。
2. 相似度计算。
3. TopK 最近邻搜索。
4. 元数据过滤。
5. 向量索引。
6. 数据更新和删除。
7. 查询性能与召回率的平衡。

向量检索并不等于完整 RAG。数据库负责存储和检索，Embedding 模型负责文本向量化，后续还需要由应用层组织检索结果和模型调用。

## 1.2 PostgreSQL 与 pgvector 的关系

三者不是同一种东西。

| 名称       | 定位                 | 作用                                             |
| ---------- | -------------------- | ------------------------------------------------ |
| PostgreSQL | 关系型数据库管理系统 | 管理表、事务、SQL、索引、权限和数据持久化        |
| Extension  | PostgreSQL 扩展机制  | 为 PostgreSQL 增加额外的数据类型、函数、操作符等 |
| pgvector   | PostgreSQL 向量扩展  | 提供向量类型、距离计算和向量索引                 |

关系：

```text
Spring Boot 应用
       |
       | JDBC
       v
PostgreSQL
       |
       | Extension
       v
pgvector
       |
       +-- vector(N)
       +-- 向量距离运算符
       +-- HNSW
       +-- IVFFlat
```

PostgreSQL 本身是关系型数据库。安装 pgvector 扩展后，它能够存储和检索向量。

因此：

- PostgreSQL 提供关系型数据库能力。
- pgvector 增加向量数据类型和向量检索能力。
- 两者组合后，可以在同一数据库中管理业务数据、元数据和向量。

## 1.3 Milvus 的技术定位

Milvus 是面向向量检索场景设计的专用向量数据库。

它围绕向量数据组织、索引构建、查询节点、数据节点和分布式资源管理设计。

Milvus 的主要优势：

- 面向大规模向量检索设计。
- 支持多种向量索引。
- 支持标量字段过滤。
- 支持分布式部署。
- 查询和数据处理组件能够独立扩展。
- 能够将向量检索资源与业务关系型数据库分离。

Milvus 的架构和部署模式随版本演进。当前官方文档区分 Milvus Lite、Standalone 和 Distributed 等部署方式，不能简单认为所有 Milvus 部署都必须使用完整分布式集群。

## 1.4 架构对比

### PostgreSQL + pgvector

```text
                 Spring Boot
                      |
                     JDBC
                      |
                PostgreSQL
                      |
       +--------------+--------------+
       |              |              |
    业务表          元数据表       向量表
       |              |              |
       +--------------+--------------+
                      |
                   pgvector
                      |
               HNSW / IVFFlat
                      |
                向量相似度查询
```

特点：

- 关系数据和向量数据统一管理。
- SQL 可以组合业务条件和向量检索。
- 使用 PostgreSQL 事务机制管理相关数据。
- 数据库运维体系相对统一。

### Milvus 分布式架构

```text
                 应用服务
                    |
                 Milvus API
                    |
                  Proxy
                    |
          +---------+---------+
          |                   |
       Query Node           Data Node
          |                   |
       向量查询              数据处理
          |                   |
          +---------+---------+
                    |
             元数据与存储层
          +---------+---------+
          |         |         |
         etcd    Object      WAL
                 Storage
```

这是概念化结构，不代表每个版本或部署模式的组件完全相同。

Milvus 官方架构包含协调、查询、数据处理和存储等组件。分布式部署可以独立扩展部分工作节点。

---

# 2. Milvus 与 PostgreSQL/pgvector 选型矩阵

## 2.1 核心能力对比

| 比较维度   | PostgreSQL + pgvector                  | Milvus                                       |
| ---------- | -------------------------------------- | -------------------------------------------- |
| 技术定位   | 关系型数据库 + 向量扩展                | 专用向量数据库                               |
| 核心设计   | 关系数据与向量数据统一管理             | 面向向量检索和分布式数据管理                 |
| 数据组织   | Database、Schema、Table、Row           | Collection、Partition、Entity                |
| 向量类型   | vector、halfvec、bit、sparsevec 等     | Dense、Sparse 等向量字段                     |
| SQL 能力   | 完整 PostgreSQL SQL                    | 使用 Milvus API 和过滤表达式                 |
| 事务       | PostgreSQL ACID 事务                   | 不应直接等同于 PostgreSQL 的通用多表事务语义 |
| 元数据过滤 | SQL WHERE                              | Scalar Filtering                             |
| 向量索引   | HNSW、IVFFlat                          | 多种专用向量索引                             |
| 全文检索   | PostgreSQL 全文检索能力                | 支持相关文本检索能力，具体取决于版本         |
| 混合查询   | SQL 中组合向量与关系条件               | 向量检索与标量过滤等能力                     |
| 水平扩展   | PostgreSQL 通常需要额外架构设计        | 分布式模式支持组件级扩展                     |
| 高可用     | PostgreSQL 主从、复制、故障切换等      | 分布式组件冗余和集群部署                     |
| 运维复杂度 | 已有 PostgreSQL 运维经验时较低         | 需要掌握专用数据库及其组件                   |
| 资源隔离   | 主要依赖 PostgreSQL 实例和系统资源配置 | 可对不同查询、数据处理节点进行资源隔离       |
| 备份恢复   | pg_dump、pg_basebackup、WAL 等         | 依赖 Milvus 及底层存储的备份恢复方案         |
| 适用规模   | 中小型到较大规模，需基准测试           | 面向大规模和高并发向量检索场景               |
| 团队维护   | 关系型数据库团队通常容易接手           | 需要专用向量数据库运维能力                   |

注意：

1. Milvus 也有 Standalone 部署，不应把它描述为只能通过 Kubernetes 部署。
2. pgvector 并非只能用于小规模数据。实际边界取决于数据量、维度、索引、硬件、过滤条件和并发。
3. Milvus 的分布式扩展能力更强，但不意味着所有业务都需要分布式向量数据库。

## 2.2 部署与运维成本

| 维度     | PostgreSQL + pgvector                 | Milvus                                                |
| -------- | ------------------------------------- | ----------------------------------------------------- |
| 基础部署 | PostgreSQL 容器 + pgvector            | Lite、Standalone 或 Distributed                       |
| 外部组件 | 当前方案无需额外引入向量数据库集群    | Distributed 通常涉及元数据、对象存储和 WAL 等基础设施 |
| 备份     | PostgreSQL 备份工具链                 | 需要覆盖 Milvus 数据及相关基础设施                    |
| 监控     | PostgreSQL 指标、慢 SQL、连接池、索引 | Milvus 组件、节点、存储和查询指标                     |
| 故障排查 | PostgreSQL 日志、SQL、执行计划        | 需要分析 Milvus 组件及存储依赖                        |
| 学习成本 | SQL、PostgreSQL、pgvector             | Collection、索引、分布式架构及专用 API                |
| 资源管理 | 统一在 PostgreSQL 实例内              | 可按组件独立规划资源                                  |

Milvus 3.x 的默认基础设施与较早版本有所变化。例如当前文档介绍了 Woodpecker、etcd 和对象存储等组件。因此，比较部署成本时必须注明 Milvus 版本与部署模式，不能直接拿不同年代的部署架构进行比较。

## 2.3 当前项目为什么选择 PostgreSQL/pgvector？

当前设备故障知识库具有以下特点：

1. 处于 M3 学习和工程验证阶段。
2. 需要掌握 RAG 的向量存储与检索链路。
3. 已经建立 PostgreSQL 18.7 + pgvector 0.8.7 环境。
4. 已有稳定的数据卷和 JDBC 连接路径。
5. 设备型号、故障编码、知识来源、租户等元数据具有明显的关系型特征。
6. 当前没有经过真实压测证明需要独立的分布式向量数据库。

因此选择 PostgreSQL/pgvector 的理由是：

- 不需要额外引入一套专用向量数据库。
- 可以通过 SQL 管理向量与业务元数据。
- 可以利用 PostgreSQL 事务处理文档、Chunk 和相关业务记录。
- 便于使用 Java JDBC 和 Spring AI 集成。
- 当前学习重点是理解 RAG 工程，而不是提前建设复杂分布式基础设施。

**这是阶段性工程选型，不是对 Milvus 的否定。**

## 2.4 什么时候考虑迁移到 Milvus？

出现以下情况时，应重新评估：

| 触发条件                 | 需要关注的问题                    |
| ------------------------ | --------------------------------- |
| 向量数量持续增长         | pgvector 索引体积、内存和查询延迟 |
| 高并发查询成为主要负载   | PostgreSQL CPU、连接池和查询排队  |
| 向量检索影响业务 SQL     | 是否需要独立资源池和独立检索服务  |
| 需要独立扩展查询节点     | PostgreSQL 当前架构是否满足       |
| 大规模向量导入和索引构建 | 索引构建时间、写入吞吐和资源竞争  |
| 多租户检索隔离复杂       | 分区、独立索引或独立集群的成本    |
| 专用向量能力成为核心需求 | Milvus 的索引和检索能力是否更合适 |

迁移前必须准备：

1. 真实向量数量。
2. 向量维度和 Embedding 模型。
3. 查询 QPS。
4. 写入吞吐。
5. TopK 分布。
6. P50、P95、P99 延迟。
7. Recall@K。
8. 元数据过滤比例。
9. 索引构建时间。
10. 资源使用情况。

不能仅凭“向量数量超过某个固定数字”就决定迁移。应通过真实数据和压测确定。

---

# 3. PostgreSQL / pgvector 核心原理

## 3.1 PostgreSQL Extension

PostgreSQL 支持扩展机制。

Extension 可以增加：

- 数据类型。
- 函数。
- 操作符。
- 索引访问方法。
- 其他数据库对象。

pgvector 就是通过 PostgreSQL Extension 机制增加向量能力。

安装扩展与在数据库中启用扩展是两个概念：

- 安装：服务器具备 pgvector 扩展文件。
- 启用：在具体数据库中执行 `CREATE EXTENSION vector`。

一个 PostgreSQL 实例可以包含多个数据库。扩展启用状态需要按数据库检查。

查看扩展：

```sql
SELECT
    extname,
    extversion
FROM pg_extension
WHERE extname = 'vector';
```

当前项目已核验结果：

```text
extname | extversion
--------+-----------
vector  | 0.8.7
```

查看扩展是否可用：

```sql
SELECT
    name,
    default_version,
    installed_version
FROM pg_available_extensions
WHERE name = 'vector';
```

`pg_available_extensions` 用于查看服务器可用的扩展及安装状态；`pg_extension` 用于查看当前数据库已经安装的扩展。

## 3.2 vector(N) 数据类型

pgvector 提供 `vector` 类型，用于保存固定维度的浮点向量。

例如：

```sql
embedding vector(384)
```

表示：

- 字段类型是 pgvector 的 vector。
- 每个向量必须具有 384 个维度。
- 插入其他维度的向量会产生类型或维度不匹配错误。

如果 Embedding 模型输出：

```text
[0.12, 0.35, 0.47, ...]
```

其长度是 384，那么数据库列应该定义为：

```sql
embedding vector(384)
```

不能因为另一个模型也是 Embedding 模型，就假设它也输出 384 维。

### 向量维度的工程意义

维度决定：

1. 每条向量的数据大小。
2. 向量距离计算的计算量。
3. 索引存储成本。
4. 向量模型与数据库字段的兼容性。

pgvector 的 `vector` 类型每个维度使用 4 字节浮点数，另有少量类型元数据开销。

384 维向量的主要浮点数据约为：

```text
384 × 4 = 1536 Bytes
```

这只是向量数据本身的估算，不包含 PostgreSQL 行开销、索引、元数据和存储对齐等成本。

## 3.3 向量存储操作

### 创建测试表

当前项目 Embedding 维度为 384，因此使用 `vector(384)`。

```sql
CREATE TABLE IF NOT EXISTS pgvector_demo (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 插入测试向量

为了避免手工输入 384 个浮点数，使用 PostgreSQL 数组构造测试向量。

下面生成三个不同方向的 384 维向量：

```sql
INSERT INTO pgvector_demo (content, embedding)
VALUES
(
    '设备启动异常',
    (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384)
),
(
    '电机运行异常',
    (
        ARRAY[0.0::REAL, 1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[382])
    )::VECTOR(384)
),
(
    '冷却系统温度过高',
    (
        ARRAY[0.0::REAL, 0.0::REAL, 1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[381])
    )::VECTOR(384)
);
```

说明：

- 这三个向量是为了验证数据库操作而构造的测试向量。
- 它们不是 Embedding 模型的真实输出。
- 不能用这些人工向量判断真实语义检索效果。

### 查看数据

```sql
SELECT
    id,
    content,
    vector_dims(embedding) AS dimensions,
    created_at
FROM pgvector_demo;
```

### 更新向量

```sql
UPDATE pgvector_demo
SET content = '设备启动故障'
WHERE id = 1;
```

真实业务中，如果文本内容变化，需要判断是否需要重新计算 Embedding。只更新文本而不更新向量，会导致文本与向量语义不一致。

### 删除数据

```sql
DELETE FROM pgvector_demo
WHERE id = 3;
```

生产环境删除 Chunk 时，还要处理对应文档、索引数据和业务引用关系。

---

# 4. 向量相似度与距离运算

## 4.1 为什么需要距离度量？

Embedding 模型将文本转换为向量后，数据库需要比较查询向量和知识库向量之间的距离。

例如：

```text
查询向量：Q
知识向量：A
知识向量：B
知识向量：C
```

数据库计算：

```text
distance(Q, A)
distance(Q, B)
distance(Q, C)
```

再根据距离排序，返回最接近的 TopK 记录。

不同距离函数对向量关系的定义不同。

## 4.2 L2 Distance：欧氏距离

L2 距离也叫 Euclidean Distance。

公式：

\[
d(A,B)=\sqrt{\sum_{i=1}^{n}(A_i-B_i)^2}
\]

特点：

- 计算两个向量在空间中的直线距离。
- 距离越小，表示向量越接近。
- 同时受方向和向量长度影响。

pgvector 运算符：

```sql
<->
```

示例：

```sql
SELECT
    id,
    content,
    embedding <-> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS distance
FROM pgvector_demo
ORDER BY distance
LIMIT 5;
```

## 4.3 Inner Product：内积

内积公式：

\[
A \cdot B=\sum_{i=1}^{n}A_iB_i
\]

内积越大，通常表示两个向量在方向和模长上具有更大的投影关系。

pgvector 运算符：

```sql
<#>
```

需要特别注意：

**pgvector 的 `<#>` 返回负内积，而不是正内积。**

因此，使用 `<#>` 按升序排列时，负内积越小，原始内积越大。

```sql
SELECT
    id,
    content,
    embedding <#> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS negative_inner_product
FROM pgvector_demo
ORDER BY embedding <#> (
    ARRAY[1.0::REAL] ||
    ARRAY_FILL(0.0::REAL, ARRAY[383])
)
LIMIT 5;
```

内积适用于模型和检索配置明确采用内积度量的情况。

如果向量没有归一化，向量长度也会影响内积结果。

## 4.4 Cosine Similarity：余弦相似度

余弦相似度计算两个向量夹角的余弦值。

公式：

\[
\operatorname{cosine}(A,B)
=
\frac{A\cdot B}{\|A\|\|B\|}
\]

特点：

- 主要比较方向。
- 不直接受向量长度大小影响。
- 常用于文本 Embedding 语义检索。

pgvector 的运算符：

```sql
<=>
```

需要注意：

`<=>` 返回的是 Cosine Distance，而不是 Cosine Similarity。

两者关系：

\[
\operatorname{CosineDistance}
=
1-\operatorname{CosineSimilarity}
\]

因此：

- Cosine Similarity 越大越相似。
- Cosine Distance 越小越相似。

查询：

```sql
SELECT
    id,
    content,
    embedding <=> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS cosine_distance
FROM pgvector_demo
ORDER BY cosine_distance
LIMIT 5;
```

如果需要显示相似度：

```sql
SELECT
    id,
    content,
    1 - (
        embedding <=> (
            ARRAY[1.0::REAL] ||
            ARRAY_FILL(0.0::REAL, ARRAY[383])
        )::VECTOR(384)
    ) AS cosine_similarity
FROM pgvector_demo
ORDER BY embedding <=> (
    ARRAY[1.0::REAL] ||
    ARRAY_FILL(0.0::REAL, ARRAY[383])
)
LIMIT 5;
```

## 4.5 三种运算符对比

| 度量            | pgvector 运算符 | 返回值   | 排序方向             |
| --------------- | --------------- | -------- | -------------------- |
| L2 Distance     | `<->`           | 欧氏距离 | 越小越相似           |
| Inner Product   | `<#>`           | 负内积   | 越小表示原始内积越大 |
| Cosine Distance | `<=>`           | 余弦距离 | 越小越相似           |

### 关键区别

| 比较     | L2       | Inner Product          | Cosine         |
| -------- | -------- | ---------------------- | -------------- |
| 关注方向 | 是       | 是                     | 是             |
| 关注模长 | 是       | 是                     | 否             |
| 结果含义 | 空间距离 | 投影关系               | 夹角关系       |
| 常见用途 | 距离度量 | 已归一化或模型指定内积 | 文本语义相似度 |

如果两个向量都进行了 L2 归一化：

\[
\|A\|=\|B\|=1
\]

那么：

\[
A\cdot B=\operatorname{CosineSimilarity}(A,B)
\]

同时：

\[
\|A-B\|^2=2-2\operatorname{CosineSimilarity}(A,B)
\]

因此，对单位向量来说，内积、余弦相似度和 L2 距离具有相同的排序关系。

但不能因此认为所有 Embedding 模型都可以随意切换距离度量。应遵循模型和检索方案的设计要求。

---

# 5. 精确搜索与 ANN 近似搜索

## 5.1 精确搜索（Exact Search）

精确搜索会对满足查询条件的候选向量进行距离计算，再按距离排序。

例如：

```sql
SELECT
    id,
    content,
    embedding <=> '[...]'::VECTOR(384) AS distance
FROM pgvector_demo
ORDER BY embedding <=> '[...]'::VECTOR(384)
LIMIT 5;
```

这里的 `[...]` 只是示意，实际 SQL 必须传入完整 384 维向量。

特点：

- 不依赖近似向量索引。
- 在给定候选集合内可以得到精确最近邻。
- 数据量较小时可能很快。
- 数据量增长后，逐行计算的成本可能变高。

## 5.2 ANN：Approximate Nearest Neighbor

ANN 即近似最近邻搜索。

它通过索引结构减少需要访问的候选向量数量，从而提高查询速度。

代价是：

- 不保证返回全局精确 TopK。
- 可能漏掉真实最近邻。
- 参数和数据分布会影响召回率。

pgvector 支持：

- HNSW
- IVFFlat

两者都属于近似最近邻索引。

### Exact 与 ANN 对比

| 比较             | Exact                      | ANN                |
| ---------------- | -------------------------- | ------------------ |
| 是否遍历全部候选 | 通常需要                   | 不需要全部访问     |
| 结果准确性       | 精确                       | 近似               |
| 查询性能         | 数据量大时可能较慢         | 通常更快           |
| 召回率           | 100%（相对当前候选集合）   | 可能低于 100%      |
| 索引成本         | 不需要 ANN 索引            | 需要构建和维护索引 |
| 适用场景         | 小规模、基准对照、精确检索 | 大规模、高性能检索 |

**重要：** ANN 的召回率应该通过真实数据与精确搜索结果对比测量，而不是凭感觉判断。

---

# 6. HNSW 与 IVFFlat 索引

## 6.1 HNSW 原理

HNSW 全称：

Hierarchical Navigable Small World。

中文通常称为分层可导航小世界图。

它是一种基于图结构的近似最近邻搜索算法。

基本思路：

1. 将向量作为图节点。
2. 根据向量之间的距离建立连接。
3. 构造多层图结构。
4. 高层节点较少，用于快速定位搜索区域。
5. 底层节点更密集，用于精细搜索。
6. 查询时从高层开始逐步向更接近目标的区域移动。

示意：

```text
             高层图
          A -------- C
          |          |
          |          |
       中间层图
       A --- B --- C --- D
       |     |     |     |
       |     |     |     |
       底层图
    A -- B -- C -- D -- E -- F -- G
```

这只是概念示意，不代表实际 HNSW 图的固定结构。

### HNSW 优势

- 查询性能通常较好。
- 不需要先进行聚类训练。
- 可以在空表上创建索引。
- 具有较好的速度与召回率权衡。

### HNSW 劣势

- 索引构建时间可能较长。
- 内存占用相对较高。
- 插入和更新会带来索引维护成本。
- 参数需要结合数据集调优。

## 6.2 HNSW 核心参数

| 参数              | 默认值 | 作用                     | 调整影响                                     |
| ----------------- | -----: | ------------------------ | -------------------------------------------- |
| `m`               |     16 | 每个节点连接数量相关参数 | 增大通常提高图连接能力，也增加内存和构建成本 |
| `ef_construction` |     64 | 构建图时的候选搜索规模   | 增大通常有利于图质量，但构建更慢             |
| `hnsw.ef_search`  |     40 | 查询时动态候选列表大小   | 增大通常提高召回率，但查询更慢               |

创建索引：

```sql
CREATE INDEX IF NOT EXISTS idx_pgvector_demo_hnsw
ON pgvector_demo
USING hnsw (embedding vector_cosine_ops);
```

指定参数：

```sql
CREATE INDEX IF NOT EXISTS idx_pgvector_demo_hnsw
ON pgvector_demo
USING hnsw (
    embedding vector_cosine_ops
)
WITH (
    m = 16,
    ef_construction = 64
);
```

查询时调整搜索候选规模：

```sql
SET hnsw.ef_search = 100;
```

该设置影响当前数据库会话。生产环境应结合连接池和事务范围谨慎管理会话参数。

## 6.3 IVFFlat 原理

IVFFlat 全称：

Inverted File with Flat Compression。

核心思想是：

1. 对向量进行聚类。
2. 将向量分配到不同聚类列表。
3. 查询时先定位距离较近的聚类。
4. 只搜索部分列表中的向量。

示意：

```text
全部向量
   |
   +---- 聚类 A
   |       +-- Vector 1
   |       +-- Vector 2
   |
   +---- 聚类 B
   |       +-- Vector 3
   |       +-- Vector 4
   |
   +---- 聚类 C
           +-- Vector 5
           +-- Vector 6
```

查询时不需要搜索所有聚类，而是选择部分聚类进行搜索。

### IVFFlat 优势

- 索引结构相对直观。
- 可以控制查询时访问的聚类数量。
- 在合适的数据规模和参数下能够获得不错的速度与召回率平衡。

### IVFFlat 劣势

- 创建索引前需要有足够数据用于聚类。
- 数据分布变化可能影响聚类效果。
- 查询时只探测部分列表，可能漏掉真实最近邻。
- 参数需要结合数据集调优。

### IVFFlat 参数

| 参数             | 作用                 |
| ---------------- | -------------------- |
| `lists`          | 聚类列表数量         |
| `ivfflat.probes` | 查询时探测的列表数量 |

创建索引：

```sql
CREATE INDEX IF NOT EXISTS idx_pgvector_demo_ivfflat
ON pgvector_demo
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
```

查询时：

```sql
SET ivfflat.probes = 10;
```

注意：`lists = 100` 只是演示参数，不是适用于所有数据集的生产推荐值。实际应结合向量数量和召回率测试。

## 6.4 HNSW 与 IVFFlat 对比

| 比较维度     | HNSW               | IVFFlat                          |
| ------------ | ------------------ | -------------------------------- |
| 核心结构     | 多层图             | 聚类倒排列表                     |
| 是否需要训练 | 不需要聚类训练     | 需要聚类                         |
| 空表创建索引 | 可以               | 不适合                           |
| 构建速度     | 通常较慢           | 取决于聚类和数据规模             |
| 内存占用     | 通常较高           | 通常相对较低                     |
| 查询参数     | ef_search          | probes                           |
| 召回率控制   | 增大搜索候选规模   | 增大探测列表数量                 |
| 数据变化     | 需要持续维护图     | 聚类分布可能逐渐不匹配           |
| 适用场景     | 常见高性能向量检索 | 对内存和索引结构有不同权衡的场景 |

当前项目采用 HNSW，是为了先掌握主流图索引的工作方式，并不意味着 IVFFlat 没有使用价值。

---

# 7. vector_cosine_ops 与索引操作符类

## 7.1 什么是操作符类？

PostgreSQL 索引操作符类用于定义索引如何理解某种数据类型上的操作。

pgvector 提供不同的向量距离操作符类：

| 距离度量      | HNSW 操作符类       | IVFFlat 操作符类    |
| ------------- | ------------------- | ------------------- |
| L2            | `vector_l2_ops`     | `vector_l2_ops`     |
| Inner Product | `vector_ip_ops`     | `vector_ip_ops`     |
| Cosine        | `vector_cosine_ops` | `vector_cosine_ops` |

例如：

```sql
CREATE INDEX idx_embedding_cosine
ON pgvector_demo
USING hnsw (embedding vector_cosine_ops);
```

表示：

- 使用 HNSW 索引。
- 索引字段是 embedding。
- 索引按 Cosine Distance 的语义组织。

如果业务查询使用：

```sql
ORDER BY embedding <=> query_vector
```

那么应使用与 Cosine Distance 对应的操作符类。

如果索引使用 `vector_l2_ops`，而查询使用 Cosine Distance，就不能假设该索引可以服务这个查询。

## 7.2 为什么普通 B-tree 不适合直接索引向量距离？

B-tree 擅长：

- 等值查询。
- 范围查询。
- 排序。
- 主键和普通字段查找。

例如：

```sql
CREATE INDEX idx_device_model
ON device_fault (device_model);
```

适合：

```sql
WHERE device_model = 'MOTOR-X1'
```

但是向量最近邻查询需要：

```sql
ORDER BY embedding <=> query_vector
LIMIT 10
```

这是基于向量距离的最近邻搜索，不是普通字段的等值或范围查找。

pgvector 使用 HNSW 和 IVFFlat 等专用索引结构解决这个问题。

**工程结论：** 普通业务字段继续使用 B-tree、GIN 等适合的索引；向量字段根据距离度量使用对应的向量索引。

---

# 8. SQL 过滤与向量检索

## 8.1 业务过滤为什么重要？

设备故障知识库不仅需要语义相似，还需要满足业务条件。

例如：

- 设备型号必须匹配。
- 故障编码必须匹配。
- 知识文档必须处于有效状态。
- 用户只能访问有权限的知识。
- 租户之间不能互相访问数据。

示例：

```sql
SELECT
    id,
    content,
    embedding <=> :query_vector AS distance
FROM knowledge_chunk
WHERE device_model = :device_model
  AND status = 'PUBLISHED'
ORDER BY embedding <=> :query_vector
LIMIT 10;
```

这属于向量检索与业务条件过滤组合。

## 8.2 过滤对 ANN 的影响

pgvector 的近似索引查询中，过滤条件可能在索引扫描后应用。

例如：

```sql
SELECT *
FROM knowledge_chunk
WHERE device_model = 'MOTOR-X1'
ORDER BY embedding <=> :query_vector
LIMIT 10;
```

如果 HNSW 初始候选集合只有 40 个，而只有 10% 的记录符合过滤条件，那么平均可能只有约 4 条记录满足条件。

因此，最终返回数量可能小于 TopK。

这不是简单的 SQL 语法错误，而是近似索引候选搜索与过滤执行顺序带来的问题。

## 8.3 Iterative Index Scan

pgvector 从 0.8.0 开始支持迭代索引扫描。

它允许索引扫描在需要时继续访问更多候选，直到满足数量要求或达到扫描上限。

HNSW 示例：

```sql
SET hnsw.iterative_scan = strict_order;
```

也可以使用：

```sql
SET hnsw.iterative_scan = relaxed_order;
```

区别：

- `strict_order`：保证最终结果严格按距离排序。
- `relaxed_order`：允许一定程度的顺序放宽，以改善召回表现。

生产环境需要结合 TopK、过滤比例、延迟和召回率测试。

## 8.4 TopK、相似度阈值和过滤条件的区别

| 概念          | 作用             | 示例                           |
| ------------- | ---------------- | ------------------------------ |
| TopK          | 限制返回数量     | 返回最接近的 10 条             |
| 相似度阈值    | 限制结果相关程度 | 只保留相似度达到业务标准的记录 |
| Metadata 过滤 | 限制候选数据范围 | 只查询某型号设备的知识         |

三者不能互相替代。

例如：

```sql
SELECT
    id,
    content,
    1 - (embedding <=> :query_vector) AS similarity
FROM knowledge_chunk
WHERE device_model = :device_model
ORDER BY embedding <=> :query_vector
LIMIT 10;
```

这里：

- `WHERE` 是业务过滤。
- `ORDER BY` 是相似度排序。
- `LIMIT 10` 是 TopK。

如果需要相似度阈值，还要额外设计距离过滤逻辑。

不要把某个固定 Cosine Similarity 值当作所有模型通用的可信度阈值。不同模型、任务和数据集的相似度分布可能不同。

---

# 9. EXPLAIN ANALYZE 查询计划分析

## 9.1 为什么要分析查询计划？

SQL 写了 HNSW 索引，不代表每次查询一定使用 HNSW。

PostgreSQL 查询优化器会根据：

- 数据量。
- 统计信息。
- 查询条件。
- 预估成本。
- 索引可用性。

决定使用哪种执行计划。

因此需要通过执行计划验证索引是否被使用。

## 9.2 查看索引

```sql
SELECT
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'pgvector_demo';
```

查看索引访问方法：

```sql
SELECT
    indexrelid::REGCLASS AS index_name,
    am.amname AS index_type
FROM pg_index i
JOIN pg_class c
    ON c.oid = i.indexrelid
JOIN pg_am am
    ON am.oid = c.relam
WHERE i.indrelid = 'pgvector_demo'::REGCLASS;
```

## 9.3 查看执行计划

```sql
EXPLAIN
SELECT
    id,
    content
FROM pgvector_demo
ORDER BY embedding <=> :query_vector
LIMIT 5;
```

查看实际执行：

```sql
EXPLAIN ANALYZE
SELECT
    id,
    content
FROM pgvector_demo
ORDER BY embedding <=> :query_vector
LIMIT 5;
```

注意：

- `EXPLAIN` 只展示计划，不执行查询。
- `EXPLAIN ANALYZE` 会实际执行 SQL。
- 对于 `INSERT`、`UPDATE`、`DELETE` 等写操作，使用 `EXPLAIN ANALYZE` 会真正执行写操作，必要时应放入事务并回滚。

## 9.4 如何判断 HNSW 是否生效？

执行计划可能包含：

```text
Limit
  -> Index Scan using idx_pgvector_demo_hnsw
       Order By: (embedding <=> ...)
```

关键是：

```text
Index Scan using idx_pgvector_demo_hnsw
```

如果看到：

```text
Seq Scan
  Sort
```

表示当前查询采用了顺序扫描和排序，而不是 HNSW 索引扫描。

但这不一定意味着索引配置错误。

测试表只有少量记录时，顺序扫描可能比使用索引更便宜。

## 9.5 如何进行诊断？

先确保表中有足够数量的数据，并执行：

```sql
ANALYZE pgvector_demo;
```

再执行：

```sql
EXPLAIN ANALYZE
SELECT
    id,
    content
FROM pgvector_demo
ORDER BY embedding <=> :query_vector
LIMIT 5;
```

如果需要验证索引路径，可以在测试会话中临时执行：

```sql
SET enable_seqscan = off;
```

然后重新执行 `EXPLAIN ANALYZE`。

注意：

- 这只是诊断手段。
- 不应直接把关闭顺序扫描当作生产调优方案。
- 小表不使用 HNSW 可能是正确的优化器决策。
- 最终应通过真实数据规模和查询负载判断。

---

# 10. RAG 业务数据模型设计

## 10.1 RAG 数据关系

设备故障知识库的数据关系可以抽象为：

```text
Knowledge Document
        |
        | 1:N
        v
Knowledge Chunk
        |
        | 1:1
        v
Embedding Vector
        |
        +---- Metadata
        |
        +---- Source Reference
        |
        +---- Device Model
        |
        +---- Fault Code
```

核心概念：

| 数据      | 作用                     |
| --------- | ------------------------ |
| Document  | 原始知识文档             |
| Chunk     | 从文档中切分出的知识片段 |
| Embedding | Chunk 对应的向量         |
| Metadata  | 业务属性和过滤字段       |
| Source    | 文档来源和可追溯信息     |

本节只关注数据库设计，不展开文档解析和 Chunking 算法。

## 10.2 文档表设计

```sql
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    document_code VARCHAR(128) NOT NULL UNIQUE,
    title VARCHAR(512) NOT NULL,
    source_type VARCHAR(64),
    source_uri TEXT,
    document_version VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

字段说明：

| 字段             | 作用           |
| ---------------- | -------------- |
| document_code    | 文档业务编号   |
| title            | 文档标题       |
| source_type      | 来源类型       |
| source_uri       | 来源地址或路径 |
| document_version | 文档版本       |
| status           | 文档状态       |
| created_at       | 创建时间       |
| updated_at       | 更新时间       |

## 10.3 Chunk 表设计

```sql
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGSERIAL PRIMARY KEY,
    chunk_code VARCHAR(128) NOT NULL UNIQUE,
    document_id BIGINT NOT NULL
        REFERENCES knowledge_document(id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    device_model VARCHAR(128),
    fault_code VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (document_id, chunk_index)
);
```

字段设计原则：

- `document_id` 用于关联原始文档。
- `chunk_index` 用于标识文档内的片段顺序。
- `content` 保存可检索文本。
- `device_model` 和 `fault_code` 用于高频业务过滤。
- `metadata` 保存不适合固定为列的扩展属性。
- `status` 用于管理有效性。

高频过滤字段优先考虑独立列，而不是全部塞进 JSONB。

## 10.4 Embedding 向量表设计

```sql
CREATE TABLE IF NOT EXISTS knowledge_embedding (
    id BIGSERIAL PRIMARY KEY,
    chunk_id BIGINT NOT NULL UNIQUE
        REFERENCES knowledge_chunk(id),
    embedding VECTOR(384) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    model_version VARCHAR(128),
    dimensions INTEGER NOT NULL DEFAULT 384,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_embedding_dimensions
        CHECK (dimensions = 384)
);
```

说明：

- 当前模型输出 384 维，所以使用 `VECTOR(384)`。
- `embedding_model` 用于记录模型标识。
- `model_version` 用于记录模型版本。
- `dimensions` 用于审计和排查。
- 外键用于保证向量对应的 Chunk 存在。

真实项目还应记录向量生成配置和预处理版本。

## 10.5 建立 HNSW 索引

```sql
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_hnsw
ON knowledge_embedding
USING hnsw (embedding vector_cosine_ops);
```

元数据索引：

```sql
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_device_model
ON knowledge_chunk (device_model);
```

```sql
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_fault_code
ON knowledge_chunk (fault_code);
```

如果需要查询 JSONB 元数据，可以根据实际查询表达式考虑 GIN 索引，而不是无条件给所有 JSONB 字段建索引。

## 10.6 业务过滤与向量表 JOIN

```sql
SELECT
    c.id AS chunk_id,
    c.content,
    c.device_model,
    c.fault_code,
    e.embedding <=> :query_vector AS distance
FROM knowledge_embedding e
JOIN knowledge_chunk c
    ON c.id = e.chunk_id
JOIN knowledge_document d
    ON d.id = c.document_id
WHERE c.device_model = :device_model
  AND c.status = 'ACTIVE'
  AND d.status = 'ACTIVE'
ORDER BY e.embedding <=> :query_vector
LIMIT 10;
```

这体现了关系型数据库的一个优势：

**可以在同一 SQL 查询中关联业务表、文档表、Chunk 表和向量表。**

实际执行计划需要通过 `EXPLAIN ANALYZE` 检查，不能仅凭 SQL 写法断言一定使用 HNSW。

---

# 11. 数据生命周期与一致性

## 11.1 文档更新

假设设备手册从 V1 更新为 V2。

可能发生：

1. 原文档内容发生变化。
2. Chunk 内容发生变化。
3. 部分 Chunk 被删除。
4. 新 Chunk 被创建。
5. 新 Embedding 被生成。
6. 旧向量不再对应当前文档内容。

因此，不能只更新 Document 表中的版本号。

需要保证文档、Chunk 和 Embedding 的版本一致。

## 11.2 事务一致性

对于同一个文档的一次更新操作，可以设计为：

```text
BEGIN
   |
   +-- 更新文档版本
   |
   +-- 删除或失效旧 Chunk
   |
   +-- 删除或失效旧 Embedding
   |
   +-- 写入新 Chunk
   |
   +-- 写入新 Embedding
   |
COMMIT
```

但如果 Embedding API 调用耗时较长，不建议简单地把远程模型调用放在一个长期数据库事务中。

更合理的工程流程通常是：

1. 记录文档更新任务。
2. 在应用层生成新 Chunk 和 Embedding。
3. 对新版本数据进行批量写入。
4. 校验数量和维度。
5. 在短事务中切换有效版本。
6. 清理旧版本数据。

这样可以缩短数据库事务持续时间，减少锁持有和连接占用。

## 11.3 幂等性

重复导入同一份文档可能产生重复 Chunk 和向量。

建议使用：

- `document_code` 唯一约束。
- `chunk_code` 唯一约束。
- `(document_id, chunk_index)` 唯一约束。
- 业务版本号。
- 内容 Hash。
- 导入任务 ID。

数据库唯一约束是幂等性的重要保障，但不能完全替代应用层的任务状态管理。

## 11.4 模型升级与向量兼容性

这是向量数据库工程中非常重要的问题。

假设：

```text
模型 A：384 维
模型 B：384 维
```

虽然两个模型的向量维度相同，但不能认为它们的向量可以直接混合检索。

原因：

- 不同模型训练目标不同。
- 向量空间的坐标含义不同。
- 相同维度不代表相同语义空间。
- 相同文本经过不同模型生成的向量通常不具备直接可比性。

甚至同一个模型在不同版本、不同指令配置或不同预处理策略下，也可能产生不同的向量分布。

因此建议记录：

| 字段                     | 作用                   |
| ------------------------ | ---------------------- |
| embedding_model          | 模型名称               |
| model_version            | 模型版本               |
| dimensions               | 向量维度               |
| embedding_schema_version | 向量结构版本           |
| preprocessing_version    | 文本预处理版本         |
| task_type                | 查询或文档任务类型     |
| normalization            | 是否归一化及归一化策略 |
| created_at               | 向量生成时间           |

模型升级时，通常需要重新生成知识库向量。

不能只把旧向量表的列类型改成新维度，就认为完成了迁移。

## 11.5 多租户和权限隔离

设备知识库可能同时服务多个客户。

至少需要区分：

- tenant_id
- document_id
- chunk_id
- device_model
- fault_code
- permission_scope

不能只依赖向量相似度判断用户是否有权限访问某条知识。

权限必须由可靠的业务过滤和数据库访问控制保证。

pgvector 官方文档还指出，共享 ANN 索引时，一个租户的数据可能影响其他租户的召回和查询效率。需要严格隔离时，应评估分区或独立表等方案。

---

# 12. 环境部署与版本固定

## 12.1 Docker 基础概念

| 概念           | 含义                                 |
| -------------- | ------------------------------------ |
| Image          | 镜像，包含运行应用所需的文件和环境   |
| Container      | 容器，镜像运行后的实例               |
| Volume         | 数据卷，用于持久化数据               |
| Port Mapping   | 端口映射，将宿主机端口映射到容器端口 |
| Docker Compose | 使用 YAML 定义和管理多容器应用       |

当前项目关系：

```text
Windows 宿主机
      |
      | JDBC :5432
      |
CentOS 7.9 虚拟机
      |
   Docker
      |
pgvector-pg18
      |
PostgreSQL 18.7
      |
pgvector 0.8.7
      |
pgvector-stack_pg18_data
```

## 12.2 为什么不能使用 latest？

例如：

```yaml
image: pgvector/pgvector:latest
```

问题：

1. 同一配置在不同时间可能拉取不同版本。
2. 依赖升级可能改变数据库行为。
3. pgvector 版本可能变化。
4. 新版本可能存在兼容性或行为变化。
5. 环境复现困难。
6. 故障排查时难以确定实际运行版本。

当前项目使用：

```text
pgvector/pgvector:pg18
```

它固定了 PostgreSQL 主版本系列，但并不等于永久固定到 PostgreSQL 18.7 和 pgvector 0.8.7。

因此还需要通过实际服务端和扩展版本查询核验。

对于要求严格复现的生产环境，可以进一步固定镜像 digest，并建立升级测试流程。

## 12.3 为什么要分别核验 PostgreSQL 和 pgvector？

因为它们不是同一个版本号。

例如：

```text
Docker Image:
pgvector/pgvector:pg18

PostgreSQL:
18.7

pgvector:
0.8.7
```

镜像标签只是镜像发布和选择方式，实际运行版本应通过数据库查询确认。

## 12.4 当前环境核验命令

以下命令仅用于读取状态，不会修改现有容器、镜像或数据卷。

在虚拟机执行：

```bash
cd /root/pgvector-stack

docker compose ps
```

查看指定容器：

```bash
docker inspect pgvector-pg18
```

查看镜像信息：

```bash
docker image inspect pgvector/pgvector:pg18
```

查看 PostgreSQL 服务端版本：

```bash
docker exec -it pgvector-pg18 \
  psql -U postgres -d spring_ai \
  -c "SELECT version();"
```

查看 pgvector 扩展版本：

```bash
docker exec -it pgvector-pg18 \
  psql -U postgres -d spring_ai \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
```

查看扩展是否可用：

```bash
docker exec -it pgvector-pg18 \
  psql -U postgres -d spring_ai \
  -c "SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name = 'vector';"
```

注意：这些命令是环境核验步骤，不代表本笔记生成时重新执行过它们。

## 12.5 CREATE EXTENSION vector

如果目标数据库尚未启用 pgvector：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

查看启用结果：

```sql
SELECT
    extname,
    extversion
FROM pg_extension
WHERE extname = 'vector';
```

当前项目已经启用，不需要为了重复验证而重新安装扩展。

## 12.6 数据库、用户和 Schema 的关系

```text
PostgreSQL Instance
        |
        +-- Database: spring_ai
                 |
                 +-- Schema: public
                 |       |
                 |       +-- Table
                 |       +-- Index
                 |       +-- Function
                 |
                 +-- Extension: vector
```

- PostgreSQL 实例：数据库服务进程及其管理的数据目录。
- Database：逻辑数据库。
- Schema：数据库中的对象命名空间。
- User/Role：数据库身份和权限主体。
- Extension：为数据库增加功能的扩展。

扩展启用状态通常是数据库级别的，不应误认为在一个数据库启用后，整个 PostgreSQL 实例的所有数据库都自动启用。

---

# 13. JDBC 与 Spring Boot 连接

## 13.1 JDBC 连接原理

JDBC 是 Java 访问关系型数据库的标准 API。

pgJDBC 是 PostgreSQL 的 Java JDBC 驱动。

连接过程：

```text
Spring Boot
    |
    v
DataSource
    |
    v
Connection Pool
    |
    v
pgJDBC
    |
    v
PostgreSQL Server
```

pgJDBC 使用 PostgreSQL 原生网络协议，属于纯 Java Type 4 驱动。

## 13.2 当前 JDBC URL

```text
jdbc:postgresql://192.168.200.153:5432/spring_ai
```

各部分含义：

| 部分            | 作用                |
| --------------- | ------------------- |
| jdbc            | JDBC 协议前缀       |
| postgresql      | PostgreSQL 驱动协议 |
| 192.168.200.153 | 数据库服务器地址    |
| 5432            | PostgreSQL 端口     |
| spring_ai       | 数据库名称          |

## 13.3 敏感配置管理

不要在 `application.yml` 中写明文密码。

可以使用环境变量：

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
```

在 Windows PowerShell 中设置当前会话环境变量：

```powershell
$env:DB_URL = "jdbc:postgresql://192.168.200.153:5432/spring_ai"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "<实际密码>"
```

然后从同一个 PowerShell 会话启动：

```powershell
mvn spring-boot:run
```

不要将实际密码写入 Git 仓库、日志、截图或 Markdown 文档。

## 13.4 JDBC Driver 版本核验

不要只因为项目使用 JDK 21，就随意复制一个 JDBC Driver 版本。

应检查 Maven 最终解析版本：

```powershell
mvn dependency:tree "-Dincludes=org.postgresql:postgresql"
```

还应检查：

```powershell
mvn help:effective-pom
```

重点确认：

- pgJDBC 版本。
- 是否由 Spring Boot 依赖管理控制。
- 是否存在多个驱动版本冲突。
- 是否使用过期或存在已知安全问题的版本。

pgJDBC 官方兼容性说明指出，当前驱动兼容 Java 8 及以上版本；具体选型仍应查看对应发布线的支持范围和 PostgreSQL 服务端兼容性。

## 13.5 Spring AI 与 pgvector 的关系

Spring AI 提供向量数据库抽象和 PGvector VectorStore 集成。

常见关系：

```text
Spring Boot
     |
     v
Spring AI
     |
     v
VectorStore
     |
     v
PgVectorStore
     |
     v
PostgreSQL + pgvector
```

需要区分：

- JDBC 连接验证：验证 Java 能否连接 PostgreSQL。
- Embedding 调用：验证模型是否能生成向量。
- VectorStore 验证：验证 Spring AI 是否能完成向量存储和检索。

这三项不是同一个测试。

当前项目应先完成 JDBC 和数据库基础 SQL 验证，再进行后续 Spring AI VectorStore 集成。

使用 Spring AI 1.1.8 时，必须以 1.1.8 对应 API 和依赖管理为准，不要直接复制其他版本的配置属性。

---

# 14. 备份、恢复和数据持久化

## 14.1 Docker Volume 的作用

容器本身是运行实例，容器删除和重建不等于数据应该丢失。

当前项目使用数据卷：

```text
pgvector-stack_pg18_data
```

其目的是让 PostgreSQL 数据目录独立于容器生命周期。

概念：

```text
Container
    |
    v
PostgreSQL Data Directory
    |
    v
Docker Volume
    |
    v
Persistent Storage
```

容器重建后，只要正确挂载原数据卷，数据库数据就应该保留。

但必须区分：

- 停止容器。
- 删除容器。
- 重建容器。
- 删除数据卷。

尤其不能把 `docker compose down -v` 当成普通停止命令，因为 `-v` 会涉及数据卷删除。

## 14.2 当前环境持久性验证

当前项目已有历史数据和旧容器保护要求。

因此不应为了验证持久化而直接执行破坏性重建。

建议在经过备份、确认目标 Compose 项目和维护窗口后，单独设计容器重建验证。

验证流程：

1. 在测试表写入唯一标识数据。
2. 查询并记录数据。
3. 确认备份可用。
4. 在维护窗口按项目实际 Compose 配置执行受控重建。
5. 确认容器仍挂载原数据卷。
6. 再次查询唯一标识数据。
7. 比较重建前后数据。

当前没有执行上述重建实验，因此不能声称已通过容器重建持久性验证。

## 14.3 pg_dump 备份

示例：

```bash
pg_dump \
  -h 192.168.200.153 \
  -p 5432 \
  -U postgres \
  -d spring_ai \
  -F c \
  -f spring_ai.dump
```

执行时会要求输入密码，或使用安全的凭据管理方式。

恢复示例：

```bash
pg_restore \
  -h 192.168.200.153 \
  -p 5432 \
  -U postgres \
  -d spring_ai \
  spring_ai.dump
```

生产恢复前必须确认目标数据库、对象冲突、权限和恢复策略。

`pg_dump` 可以在数据库正常被访问时生成一致性备份，但它不等同于完整的物理备份和时间点恢复方案。

生产环境还需要评估：

- 基础备份。
- WAL 归档。
- PITR。
- 备份异地保存。
- 定期恢复演练。

---

# 15. 性能、资源与生产工程注意事项

## 15.1 HNSW 构建成本

HNSW 的索引构建需要建立图结构。

主要影响因素：

- 向量数量。
- 向量维度。
- `m`。
- `ef_construction`。
- CPU。
- 可用内存。
- 磁盘 I/O。
- 并行维护能力。

提高构建参数通常会增加构建成本。

不能只关注查询速度，还应记录：

- 索引构建耗时。
- 索引大小。
- 构建时 CPU。
- 构建时内存。
- 构建期间业务查询延迟。

## 15.2 ANN 召回率与查询性能

ANN 调优的核心是平衡：

```text
更快的查询
     |
     v
访问更少候选
     |
     v
可能漏掉真实最近邻
```

提高搜索候选规模通常可以改善召回率，但会增加查询成本。

需要建立精确搜索基准：

1. 选定固定测试查询。
2. 使用精确搜索得到基准 TopK。
3. 使用 ANN 搜索得到结果。
4. 比较两组结果。
5. 计算 Recall@K。
6. 同时记录查询延迟。

Recall@K：

\[
Recall@K=
\frac{|ANN结果_K \cap Exact结果_K|}{K}
\]

这里的 Exact 结果是召回率评估基准。

## 15.3 数据规模与索引维护

数据增长后，应关注：

- HNSW 索引大小。
- 索引构建和写入速度。
- 死元组。
- VACUUM。
- ANALYZE。
- 查询计划。
- 磁盘空间。
- 数据库缓存命中。
- 索引维护期间的资源竞争。

不能只依赖创建索引来解决所有性能问题。

## 15.4 数据库连接池

Spring Boot 常见连接池是 HikariCP。

连接池的作用：

- 复用数据库连接。
- 避免每次请求都创建连接。
- 控制最大并发连接数。
- 管理连接超时和生命周期。

需要关注：

| 参数              | 含义               |
| ----------------- | ------------------ |
| maximumPoolSize   | 连接池最大连接数   |
| minimumIdle       | 最小空闲连接数     |
| connectionTimeout | 获取连接的等待超时 |
| idleTimeout       | 空闲连接回收时间   |
| maxLifetime       | 连接最大生命周期   |

不能盲目把连接池调得很大。

数据库最大连接数、CPU、内存和业务并发必须一起考虑。

## 15.5 超时控制

至少区分：

- 获取连接超时。
- 建立连接超时。
- SQL 执行超时。
- 应用请求超时。
- Embedding API 超时。

这些超时不是同一个参数。

向量查询变慢时，应区分：

1. 应用等待连接。
2. 数据库执行 SQL。
3. 网络传输。
4. 上游 Embedding 模型调用。
5. 应用处理查询结果。

## 15.6 SQL 注入防护

禁止通过字符串拼接直接构造用户输入 SQL。

不推荐：

```java
String sql =
    "SELECT * FROM knowledge_chunk WHERE device_model = '"
    + deviceModel + "'";
```

推荐使用参数绑定：

```java
String sql = """
    SELECT id, content
    FROM knowledge_chunk
    WHERE device_model = ?
    """;

try (PreparedStatement statement =
         connection.prepareStatement(sql)) {

    statement.setString(1, deviceModel);

    try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
            // 处理查询结果
        }
    }
}
```

注意：SQL 参数可以绑定值，不能直接绑定表名、列名或 SQL 关键字。动态排序字段需要通过白名单选择。

## 15.7 最小权限

生产应用不应该使用 PostgreSQL 超级用户运行。

建议分离：

- 数据库管理员账号。
- 数据迁移账号。
- 应用运行账号。
- 只读查询账号。

应用账号只获得完成业务所需的权限。

## 15.8 监控与慢查询

应关注：

- 活跃连接数。
- 空闲连接数。
- 慢 SQL。
- CPU 和内存。
- 磁盘空间。
- 数据库缓存命中率。
- 索引大小。
- HNSW 查询延迟。
- 事务持续时间。
- 锁等待。
- 死锁。
- VACUUM 状态。

常用系统视图：

```sql
SELECT *
FROM pg_stat_activity;
```

查看数据库大小：

```sql
SELECT
    pg_size_pretty(pg_database_size('spring_ai'));
```

查看索引大小：

```sql
SELECT
    indexrelname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
ORDER BY pg_relation_size(indexrelid) DESC;
```

---

# 16. SQL 实践验证清单

本节给出可以在当前数据库执行的验证步骤。

**注意：以下 SQL 是待执行实验脚本，不代表本笔记生成时已重新执行。**

## 16.1 验证 PostgreSQL 版本

```sql
SELECT version();
```

预期核对信息：

- PostgreSQL 主版本为 18。
- 实际小版本为 18.7。

## 16.2 验证 pgvector 版本

```sql
SELECT
    extname,
    extversion
FROM pg_extension
WHERE extname = 'vector';
```

当前已验证基线：

```text
vector | 0.8.7
```

## 16.3 验证 Extension

```sql
SELECT
    name,
    default_version,
    installed_version
FROM pg_available_extensions
WHERE name = 'vector';
```

## 16.4 验证 vector(N)

```sql
CREATE TABLE IF NOT EXISTS pgvector_validation (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL
);
```

## 16.5 插入测试向量

```sql
INSERT INTO pgvector_validation (content, embedding)
VALUES
(
    '设备启动故障',
    (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384)
),
(
    '电机运行故障',
    (
        ARRAY[0.0::REAL, 1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[382])
    )::VECTOR(384)
),
(
    '冷却系统故障',
    (
        ARRAY[0.0::REAL, 0.0::REAL, 1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[381])
    )::VECTOR(384)
);
```

## 16.6 验证向量维度

```sql
SELECT
    id,
    content,
    vector_dims(embedding)
FROM pgvector_validation;
```

## 16.7 验证 Cosine 查询

```sql
SELECT
    id,
    content,
    embedding <=> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS distance
FROM pgvector_validation
ORDER BY distance
LIMIT 5;
```

## 16.8 验证 L2 查询

```sql
SELECT
    id,
    content,
    embedding <-> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS distance
FROM pgvector_validation
ORDER BY distance
LIMIT 5;
```

## 16.9 验证 Inner Product 查询

```sql
SELECT
    id,
    content,
    embedding <#> (
        ARRAY[1.0::REAL] ||
        ARRAY_FILL(0.0::REAL, ARRAY[383])
    )::VECTOR(384) AS negative_inner_product
FROM pgvector_validation
ORDER BY negative_inner_product
LIMIT 5;
```

## 16.10 创建 HNSW 索引

```sql
CREATE INDEX IF NOT EXISTS idx_pgvector_validation_hnsw
ON pgvector_validation
USING hnsw (embedding vector_cosine_ops);
```

## 16.11 查看索引定义

```sql
SELECT
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'pgvector_validation';
```

## 16.12 分析查询计划

```sql
ANALYZE pgvector_validation;
```

```sql
EXPLAIN ANALYZE
SELECT
    id,
    content
FROM pgvector_validation
ORDER BY embedding <=> (
    ARRAY[1.0::REAL] ||
    ARRAY_FILL(0.0::REAL, ARRAY[383])
)::VECTOR(384)
LIMIT 5;
```

小表可能使用顺序扫描，这是正常的成本优化行为。不要把小表没有使用 HNSW 判定为索引创建失败。

## 16.13 JDBC 连接验证

应用运行环境为 Windows 本机。

先通过 PowerShell 检查网络端口：

```powershell
Test-NetConnection 192.168.200.153 -Port 5432
```

然后通过 Spring Boot DataSource 或 JDBC 测试连接。

连接验证至少应记录：

- JDBC URL。
- pgJDBC 版本。
- 数据库服务端版本。
- 数据库名称。
- 当前用户。
- 查询执行结果。

不要记录密码。

---

# 17. 常见故障与排查

| 现象                                  | 可能原因                           | 排查方向                               |
| ------------------------------------- | ---------------------------------- | -------------------------------------- |
| `extension "vector" is not available` | 服务端没有安装扩展文件             | 检查镜像和扩展安装状态                 |
| `type "vector" does not exist`        | 当前数据库未启用扩展               | 检查 `pg_extension`                    |
| 向量维度不匹配                        | 模型输出维度与列定义不同           | 检查 `vector_dims` 和模型配置          |
| JDBC 连接超时                         | 网络、端口映射、防火墙或数据库状态 | 检查容器状态和 Windows 端口连通性      |
| `password authentication failed`      | 账号或密码错误                     | 检查凭据和认证配置，不要将密码写入代码 |
| HNSW 索引没有被使用                   | 数据量较小或查询不匹配             | 检查计划、操作符和操作符类             |
| 查询结果少于 TopK                     | ANN 候选数量不足或过滤影响         | 检查 `ef_search`、过滤条件和迭代扫描   |
| 索引创建失败                          | 参数、类型或操作符类错误           | 检查列类型、索引定义和 pgvector 版本   |
| 数据重建后丢失                        | 数据卷挂载或重建操作错误           | 核验实际 Volume 和挂载关系             |
| 查询延迟突然升高                      | 连接池、CPU、锁、I/O 或索引问题    | 检查慢 SQL、系统资源和执行计划         |

## 17.1 排查原则

不要看到一个错误就直接重装数据库。

建议顺序：

1. 检查应用错误日志。
2. 检查 JDBC URL 和连接参数。
3. 检查 Windows 到虚拟机的网络连通性。
4. 检查容器状态。
5. 检查 PostgreSQL 服务端状态。
6. 检查扩展是否启用。
7. 检查向量维度。
8. 检查 SQL 和索引定义。
9. 检查执行计划。
10. 必要时查看数据库日志。

任何可能影响数据卷或历史容器的操作，都必须先确认影响范围。

---

# 18. 当前方案的技术边界与总结

## 18.1 PostgreSQL/pgvector 的优势

- 关系数据与向量数据统一管理。
- SQL 查询能力完整。
- 事务和约束能力成熟。
- 与 Java JDBC 集成简单。
- 可以使用 PostgreSQL 现有运维体系。
- 对当前设备知识库的业务元数据管理比较自然。

## 18.2 PostgreSQL/pgvector 的边界

- 向量检索与业务 SQL 共享数据库资源。
- HNSW 索引存在内存和构建成本。
- ANN 查询需要权衡召回率和性能。
- 过滤条件可能影响 ANN 返回数量。
- 超大规模分布式向量检索需要评估架构扩展能力。
- 数据库容量、连接数和资源隔离都需要整体规划。

## 18.3 为什么当前不选 Milvus？

当前并没有足够的压测数据证明项目需要 Milvus 的分布式向量检索能力。

在已有 PostgreSQL 环境的基础上，继续使用 pgvector 可以减少系统复杂度，让学习重点放在：

- Embedding 数据管理。
- 向量距离。
- HNSW。
- SQL 过滤。
- 数据生命周期。
- Spring AI 数据访问。
- 工程化验证。

当向量检索成为独立的高负载系统，且 PostgreSQL 无法在合理成本内满足性能和扩展要求时，再考虑迁移。

---

# 19. 实践验证记录

以下为本次知识笔记的验证状态。已知环境事实来自此前提供的环境核验信息；本笔记未重新连接用户的虚拟机或运行数据库命令。

| 验证项目              | 当前状态         | 记录                         |
| --------------------- | ---------------- | ---------------------------- |
| PostgreSQL 服务端版本 | 已有环境核验信息 | 18.7                         |
| pgvector 扩展版本     | 已有环境核验信息 | 0.8.7                        |
| vector 扩展启用       | 已有环境核验信息 | 已启用                       |
| `vector(384)` 测试表  | 待执行           | 尚未在本次任务中执行         |
| 测试向量插入          | 待执行           | 尚未在本次任务中执行         |
| Cosine 查询           | 待执行           | 尚未在本次任务中执行         |
| L2 查询               | 待执行           | 尚未在本次任务中执行         |
| Inner Product 查询    | 待执行           | 尚未在本次任务中执行         |
| HNSW 索引创建         | 待执行           | 尚未在本次任务中执行         |
| 查询计划分析          | 待执行           | 尚未在本次任务中执行         |
| 容器重建持久性        | 待执行           | 未进行破坏性重建测试         |
| Java JDBC 连接        | 待执行           | 本次未实际编译和运行连接测试 |

## 19.1 后续实验记录模板

每次执行实验时，记录：

```text
实验名称：
执行日期：
执行环境：
PostgreSQL 版本：
pgvector 版本：
Embedding 模型：
Embedding 维度：

执行命令：
[填写真实命令]

执行 SQL：
[填写真实 SQL]

真实输出：
[粘贴原始输出]

执行结果：
[通过 / 失败 / 部分通过]

异常信息：
[填写真实异常]

原因分析：
[填写分析]

修复方式：
[填写修复过程]

Git Commit：
[填写真实提交编号]
```

不要把示例输出当作真实执行记录。

---

# 20. 学习验收

完成本节后，应能独立回答：

1. PostgreSQL、Extension 和 pgvector 三者是什么关系？
2. 为什么安装扩展和启用扩展是两个概念？
3. `vector(384)` 中的 384 表示什么？
4. `<->`、`<#>` 和 `<=>` 分别计算什么？
5. 为什么 `<#>` 返回负内积？
6. 为什么 `<=>` 是 Cosine Distance，而不是 Cosine Similarity？
7. 精确搜索和 ANN 的核心区别是什么？
8. HNSW 为什么使用分层图结构？
9. HNSW 的 `m`、`ef_construction` 和 `ef_search` 分别影响什么？
10. IVFFlat 为什么需要聚类？
11. `vector_cosine_ops` 有什么作用？
12. 为什么普通 B-tree 不能直接代替 HNSW？
13. 为什么添加 HNSW 后，小表仍然可能使用 Seq Scan？
14. 为什么 ANN 过滤后可能返回不足 TopK 的结果？
15. `hnsw.iterative_scan` 解决什么问题？
16. 为什么模型维度相同也不代表向量兼容？
17. 为什么文档、Chunk 和 Embedding 需要版本关联？
18. 为什么不能把远程 Embedding API 调用放在长期数据库事务中？
19. Docker Volume 与 Container 有什么区别？
20. 为什么不能随意使用 `latest`？
21. 为什么必须分别核验 PostgreSQL、pgvector 和 pgJDBC 版本？
22. 如何通过 `EXPLAIN ANALYZE` 判断 HNSW 是否被使用？
23. 为什么数据库连接池不能盲目增大？
24. 什么情况下应考虑从 pgvector 迁移到 Milvus？

## 最终验收标准

能够独立完成以下任务：

- 解释 PostgreSQL + pgvector 的架构。
- 说明当前选择 pgvector 的工程理由。
- 说明 Milvus 的优势和迁移触发条件。
- 解释三种向量距离运算符。
- 理解 HNSW 与 IVFFlat 的基本原理。
- 独立创建 `vector(384)` 测试表。
- 插入测试向量并执行相似度查询。
- 创建 HNSW 索引。
- 使用 `EXPLAIN ANALYZE` 分析查询计划。
- 核验 PostgreSQL 和 pgvector 版本。
- 通过 Java JDBC 验证数据库连接。
- 说明数据卷持久化、备份和恢复策略。
- 解释向量模型升级时的兼容性问题。

---

# 21. 参考资料

以下优先使用官方文档和项目原始资料。涉及版本时，以对应版本文档为准。

## PostgreSQL

1. PostgreSQL 18 Documentation | PostgreSQL 18 官方文档：[https://www.postgresql.org/docs/18/](https://www.postgresql.org/docs/18/)

2. EXPLAIN | 查询执行计划说明：[https://www.postgresql.org/docs/18/sql-explain.html](https://www.postgresql.org/docs/18/sql-explain.html)

3. ANALYZE | 统计信息收集说明：[https://www.postgresql.org/docs/18/sql-analyze.html](https://www.postgresql.org/docs/18/sql-analyze.html)

4. pg_dump | PostgreSQL 数据库逻辑备份工具：[https://www.postgresql.org/docs/18/app-pgdump.html](https://www.postgresql.org/docs/18/app-pgdump.html)

## pgvector

5. pgvector: Open-source vector similarity search for Postgres | pgvector：PostgreSQL 开源向量相似度检索扩展：[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)

6. pgvector 0.8.7 Documentation | pgvector 0.8.7 文档：[https://pgvector.app/docs/](https://pgvector.app/docs/)

7. pgvector Changelog | pgvector 版本变更记录：[https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md)

## Milvus

8. Milvus Architecture Overview | Milvus 架构概览：[https://milvus.io/docs/architecture_overview.md](https://milvus.io/docs/architecture_overview.md)

9. Main Components | Milvus 主要组件：[https://milvus.io/docs/main_components.md](https://milvus.io/docs/main_components.md)

10. Overview of Milvus Deployment Options | Milvus 部署模式概览：[https://milvus.io/docs/install-overview.md](https://milvus.io/docs/install-overview.md)

11. Data Infrastructure | Milvus 数据基础设施：[https://milvus.io/docs/data-infra-integration-overview.md](https://milvus.io/docs/data-infra-integration-overview.md)

12. Basic Vector Search | Milvus 基础向量检索：[https://milvus.io/docs/single-vector-search.md](https://milvus.io/docs/single-vector-search.md)

## Java JDBC

13. Documentation | pgJDBC 官方文档：[https://jdbc.postgresql.org/documentation/](https://jdbc.postgresql.org/documentation/)

14. Compatibility | pgJDBC 兼容性说明：[https://jdbc.postgresql.org/documentation/](https://jdbc.postgresql.org/documentation/)

## Spring AI

15. PGvector | Spring AI PGvector 向量存储集成：[https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)

> Spring AI 项目实际使用版本为 1.1.8。阅读 API、依赖和配置时，应优先选择 Spring AI 1.1.8 对应的版本资料，避免直接套用其他版本的配置示例。