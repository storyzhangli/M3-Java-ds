# **费曼口述：Milvus 与 PostgreSQL/pgvector 的综合比较——为何不选择 Milvus**

说话人1: 本次围绕 70 个问题展开，分为八个阶段：核心概念、系统架构、数据模型与一致性、向量索引与检索、RAG 业务场景、部署运维、Spring AI 集成，以及当前项目的选型决策。

技术基线：JDK 21、Spring Boot 3.5.x、Spring AI 1.1.8、PostgreSQL 18.6、pgvector 0.8.x。讨论重点不是证明某个产品绝对更好，而是判断哪种方案更符合当前制造设备故障知识库的业务规模、架构现状、检索需求和团队运维能力。

先明确一个选型原则：**当前选择 PostgreSQL + pgvector，不代表 Milvus 性能不足，而是当前项目未必需要为专用向量数据库引入额外的部署、数据同步和运维复杂度。** 是否需要迁移，应由可测量的负载、延迟、召回率和资源瓶颈决定。

## **一、核心概念与产品定位**

**说话人1: 问题1，PostgreSQL、pgvector 和 Milvus 分别是什么？三者在技术定位上有什么本质区别？**

说话人1: 答，PostgreSQL 是一个通用关系型数据库，主要负责结构化数据存储、事务处理、SQL 查询和数据一致性。

pgvector 是 PostgreSQL 的一个扩展，为 PostgreSQL 增加向量数据类型、距离计算和向量索引能力。

Milvus 则是专门面向向量数据管理和相似度检索设计的向量数据库，重点解决大规模向量数据的存储、索引、检索和扩展问题。

三者的定位可以概括为：

- PostgreSQL：通用关系型数据库。
- pgvector：让 PostgreSQL 具备向量存储和检索能力的扩展。
- Milvus：专用向量数据库。

当前项目已经使用 PostgreSQL 管理设备、文档、工单等业务数据，因此 pgvector 可以让关系数据和向量数据在同一个数据库体系内协同管理。

**说话人1: 问题2，为什么说 pgvector 是 PostgreSQL 的向量扩展，而不是一个独立运行的向量数据库？**

说话人1: 答，因为 pgvector 运行在 PostgreSQL 内部，以 PostgreSQL Extension 的形式扩展数据库能力。

它依赖 PostgreSQL 提供的：

- 数据库进程和连接管理。
- SQL 执行引擎。
- 事务和 MVCC。
- WAL 和数据持久化机制。
- 权限、备份和恢复体系。

pgvector 自己提供向量类型、距离运算符和索引访问方法，例如 HNSW 和 IVFFlat。

所以，pgvector 不是一个需要单独启动、单独管理的数据库服务，而是 PostgreSQL 的扩展能力。

**说话人1: 问题3，PostgreSQL 作为关系型数据库，为什么能够支持向量存储和相似度检索？**

说话人1: 答，因为数据库本身并不只能存储整数、字符串和日期，也可以通过扩展机制增加新的数据类型和索引能力。

pgvector 定义了 `vector` 等向量类型，并实现向量之间的距离计算。

例如：

```
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(1536)
);
```

查询时可以使用距离运算符：

```
SELECT id, content
FROM document_chunk
ORDER BY embedding <=> '[...]'
LIMIT 5;
```

其中 `<=>` 表示 Cosine Distance。

PostgreSQL 负责执行 SQL 和管理数据，pgvector 负责向量类型、距离运算和向量索引。

**说话人1: 问题4，Milvus 为什么被称为专用向量数据库？它主要解决哪类问题？**

说话人1: 答，因为 Milvus 从架构设计上就围绕向量数据管理和近似最近邻检索展开。

它重点解决：

1. 大规模向量数据存储。
2. 高维向量索引构建。
3. 高并发向量检索。
4. 向量与标量字段组合过滤。
5. 多种索引算法和检索参数配置。
6. 分布式部署与水平扩展。

例如，当一个平台拥有大量用户、海量图片向量、商品向量或多模态向量，并且需要独立扩展检索资源时，Milvus 这样的专用向量数据库就可能具有明显价值。

但专用不代表所有业务都应该使用它。还需要考虑数据规模、查询负载和运维成本。

**说话人1: 问题5，什么是向量存储、向量检索和向量索引？三者之间有什么关系？**

说话人1: 答，向量存储是把 Embedding 模型生成的数值数组持久化保存。

例如，一段设备维修说明经过 Embedding 模型处理后，得到一个 1536 维向量：

```
[0.12, -0.35, 0.08, ...]
```

向量检索是根据用户问题对应的查询向量，寻找数据库中距离较近的向量。

向量索引则是为了减少检索时需要比较的候选数据量，提高查询效率。

三者的关系是：

- 向量存储解决数据保存问题。
- 向量索引解决检索效率问题。
- 向量检索解决相似知识查找问题。

没有索引也可以进行精确向量搜索，只是数据规模增大后，计算成本可能很高。

**说话人1: 问题6，精确最近邻搜索（Exact Nearest Neighbor）与近似最近邻搜索（Approximate Nearest Neighbor）有什么区别？**

说话人1: 答，精确最近邻搜索会按照指定距离度量，对符合过滤条件的候选向量进行完整比较，得到真正距离最近的结果。

近似最近邻搜索则使用 HNSW、IVFFlat 等索引，尽量减少需要访问的候选数据，以更低的查询成本找到足够接近的结果。

两者区别是：

- 精确搜索：结果精确，但大规模数据下计算成本较高。
- 近似搜索：查询通常更快，但可能漏掉部分真正最近的向量。

例如，数据库中有 100 万条设备维修 Chunk，精确搜索可能需要比较大量向量，而 ANN 索引可以缩小搜索范围。

不过 ANN 的召回率不是固定的，需要通过索引参数和真实数据集进行评估。

**说话人1: 问题7，PostgreSQL + pgvector 和 Milvus 分别更适合什么类型的数据规模与业务负载？**

说话人1: 答，PostgreSQL + pgvector 更适合希望将业务数据和向量数据统一管理、检索规模适中、事务和 SQL 关联能力重要，并且希望降低基础设施复杂度的项目。

Milvus 更适合向量数据规模较大、检索吞吐量高、需要独立扩展向量检索资源，或者需要更丰富专用向量检索能力的场景。

不能简单地说 pgvector 只能处理小数据，Milvus 才能处理大数据。

实际能力还受到向量维度、索引类型、过滤条件、硬件、数据分布、并发量和延迟目标等因素影响。

**说话人1: 问题8，为什么不能仅凭数据库是否专门为向量检索设计，就判断它是否适合当前项目？**

说话人1: 答，因为数据库选型必须围绕真实业务需求，而不是只看产品定位。

当前制造设备知识库除了向量检索，还需要：

- 设备和工单之间的关系查询。
- 文档版本和审核状态管理。
- 租户与设备权限过滤。
- 文档、Chunk 和向量之间的追溯。
- 数据更新、删除和事务一致性。
- 全文检索与结构化 SQL 查询。

如果为了向量检索引入 Milvus，却还要继续使用 PostgreSQL 保存业务数据，就需要额外处理两套数据库之间的数据同步和运维。

因此，应该比较整个系统的综合成本，而不是只比较向量检索性能。

## **二、系统架构与运行机制**

**说话人1: 问题9，PostgreSQL + pgvector 的整体架构是什么？向量数据、索引和业务数据分别存储在哪里？**

说话人1: 答，整体上仍然是一个 PostgreSQL 数据库实例，只是通过 pgvector 扩展增加了向量能力。

例如：

```
PostgreSQL
├── 设备表 equipment
├── 文档表 document
├── Chunk 表 document_chunk
│   ├── chunk_id
│   ├── document_id
│   ├── content
│   ├── metadata
│   └── embedding VECTOR(1536)
└── 向量索引
    └── HNSW / IVFFlat
```

向量本身保存在表的数据页中，索引由 PostgreSQL 的索引机制管理，业务数据仍然使用普通关系表保存。

因此，可以通过 SQL 在同一数据库中完成向量检索、Metadata Filter 和业务关联查询。

**说话人1: 问题10，Milvus 的整体架构是什么？它的访问层、协调组件、计算节点和存储层分别负责什么？**

说话人1: 答，Milvus 采用分层、模块化架构，主要包括访问层、协调层、工作节点和存储层。

- 访问层 Proxy：接收客户端请求、路由请求、汇总查询结果。
- 协调层 Coordinator：管理集群拓扑、调度任务和协调资源。
- 工作节点：执行数据写入、实时数据处理、历史数据查询、索引构建等任务。
- 存储层：保存元数据、操作日志和持久化数据。

以 Milvus 2.6 为例，架构重点包括 Proxy、Coordinator、Streaming Node、Query Node、Data Node，以及元数据存储、对象存储和 WAL。

这套架构使 Milvus 能够将不同类型的计算任务分开扩展。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

milvus.io+1



**说话人1: 问题11，Milvus Standalone、Milvus Distributed 和 Milvus Lite 分别适合什么场景？**

说话人1: 答，三种模式主要区别在部署形态和扩展能力。

- Milvus Lite：轻量级本地运行方式，适合开发验证、原型和边缘场景。
- Milvus Standalone：单机服务部署，组件集中在一个实例中，适合单机容量能够满足需求的服务场景。
- Milvus Distributed：分布式集群部署，组件可以独立扩展，适合大规模、高负载和高可用场景。

需要特别注意，Milvus Standalone 并不等于 Milvus Lite。Standalone 仍然是独立运行的数据库服务。

此外，不能默认 Standalone 可以无缝在线升级为 Distributed，部署前需要确认目标版本的迁移路径。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

Milvus Documentation+1



**说话人1: 问题12，Milvus 为什么要将计算与存储分离？这种架构解决了什么问题？**

说话人1: 答，主要是为了让计算资源和持久化存储资源能够独立扩展。

例如，某个业务白天检索请求很多，但数据写入量不大，就可以增加查询计算资源，而不必同步增加所有存储节点。

反过来，如果批量导入数据、构建索引的压力很大，也可以针对数据处理任务扩展资源。

这种架构能够提高资源利用率，并使故障恢复和水平扩展更加灵活。

但代价是系统组件、网络通信和运维复杂度增加。

**说话人1: 问题13，Milvus 的 Query Node、Data Node、Streaming Node 分别负责什么？**

说话人1: 答，在 Milvus 2.6 架构中：

- Streaming Node：负责实时数据流处理、WAL 相关操作、Growing 数据查询和部分查询协调。
- Query Node：负责加载和查询已经持久化的历史数据及其索引。
- Data Node：负责历史数据的离线处理，例如 Compaction 和索引构建。

可以简单理解为：

Streaming Node 关注实时数据处理，Query Node 关注历史数据查询，Data Node 关注离线数据处理。

注意，Milvus 2.6 的组件职责与早期版本存在演进，不能直接把旧版本的 Data Node、Index Node 职责照搬到 2.6。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

milvus.io+1



**说话人1: 问题14，Milvus 使用的元数据存储、对象存储和 WAL 分别承担什么职责？**

说话人1: 答，三者解决不同的数据管理问题。

1. 元数据存储：保存 Collection Schema、集群状态和相关元数据信息，常见实现包括 etcd。
2. 对象存储：保存持久化数据文件、索引文件和日志快照等，常见实现包括 MinIO、S3。
3. WAL：Write-Ahead Log，预写日志，记录数据变更，为数据持久化和故障恢复提供基础。

可以理解为：元数据存储管理系统信息，对象存储保存持久化数据，WAL 保障变更记录和恢复能力。

具体组件及默认依赖会随 Milvus 版本和部署模式变化。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

milvus.io+1



**说话人1: 问题15，Milvus 的分布式架构如何实现查询、数据写入和索引构建的独立扩展？**

说话人1: 答，因为 Milvus 将不同工作负载分配给不同类型的节点。

例如：

- 查询压力大：增加 Query Node 或相关查询资源。
- 实时写入压力大：扩展 Streaming Node 等实时处理资源。
- 索引构建和数据整理压力大：扩展 Data Node 的处理能力。

这样就不必像简单的单体系统一样，所有负载都共享同一组计算资源。

不过，独立扩展不代表完全没有资源关联，实际效果仍受到数据分片、网络、对象存储、缓存和集群调度影响。

**说话人1: 问题16，PostgreSQL 的单实例架构与 Milvus 的分布式架构，在扩容方式上有什么区别？**

说话人1: 答，PostgreSQL 常见的扩容方式包括：

- 垂直扩容：增加 CPU、内存和磁盘性能。
- 读扩展：通过只读副本分担部分查询负载。
- 分区：将数据按规则组织和管理。
- 分片：在更复杂的架构中拆分数据。

Milvus Distributed 则可以按照组件职责独立增加节点，进行水平扩展。

因此，PostgreSQL 的扩展通常更依赖实例资源、读写架构和数据组织；Milvus 分布式模式更强调不同计算节点的独立扩展。

**说话人1: 问题17，引入 Milvus 后，Java 应用与向量数据库之间会增加哪些网络通信、服务依赖和故障边界？**

说话人1: 答，当前如果 Java 应用只访问 PostgreSQL，那么引入 Milvus 后，至少会增加一个独立的向量数据库服务和相应的客户端通信链路。

需要额外考虑：

- Milvus 服务地址和连接池。
- Java SDK 版本及连接配置。
- 网络超时和连接异常。
- 身份认证和访问权限。
- 服务健康检查和告警。
- Milvus 与 PostgreSQL 的数据同步。
- 两套数据库之间的失败补偿。
- 部署、备份和升级流程。

例如，PostgreSQL 写入文档成功，但 Milvus 写入失败，就会出现业务数据存在、向量数据缺失的问题。

这就是引入第二套数据库后增加的故障边界。

## **三、数据模型与一致性**

**说话人1: 问题18，pgvector 如何通过&#x20;**`vector(n)`**&#x20;定义向量字段？维度不一致时会发生什么？**

说话人1: 答，可以使用 pgvector 提供的 `vector(n)` 类型定义固定维度的向量字段。

例如：

```
CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(1536)
);
```

这里的 `1536` 表示每个向量必须具有 1536 个维度。

如果插入的向量维度不符合字段要求，数据库会拒绝该数据。

需要注意，向量维度必须与 Embedding 模型输出一致。不能将 768 维模型的结果直接写入 1536 维字段。

**说话人1: 问题19，Milvus 的 Collection、Schema、Entity、Field 分别对应什么概念？**

说话人1: 答，Milvus 使用类似数据库表结构的方式组织向量数据。

- Collection：数据集合，可以类比关系数据库中的表。
- Schema：定义 Collection 的数据结构。
- Field：字段，例如主键、设备型号、文档版本和向量字段。
- Entity：一条实际数据记录，可以类比关系数据库中的一行。

例如，一个设备知识 Chunk 可以对应一个 Entity，其中包含 Chunk ID、文本内容、设备型号、文档版本和向量。

**说话人1: 问题20，PostgreSQL 的关系表、主键、外键、JSONB 和 SQL JOIN，在知识库数据管理中有什么价值？**

说话人1: 答，它们可以帮助知识库建立清晰的数据关系和业务约束。

例如：

- 设备表：保存设备型号和设备信息。
- 文档表：保存维修手册和版本信息。
- Chunk 表：保存切分后的文本。
- 工单表：保存故障处理记录。

通过主键和外键，可以建立设备、文档、Chunk 和工单之间的关系。

JSONB 则适合保存结构相对灵活的 Metadata，例如解析参数、扩展标签和来源信息。

SQL JOIN 可以根据 `document_id`、`equipment_id` 等字段进行关联查询。

这对于制造设备知识库的来源追溯和业务管理非常有价值。

**说话人1: 问题21，Milvus 的标量字段和 JSON 字段，如何保存设备型号、故障编码、文档来源等元数据？**

说话人1: 答，可以在 Collection Schema 中定义标量字段和 JSON 字段。

例如：

```
id              INT64
equipment_model VARCHAR
fault_code      VARCHAR
document_version VARCHAR
metadata        JSON
embedding       FLOAT_VECTOR
```

设备型号和故障编码等经常用于过滤的字段，可以考虑定义为明确的标量字段。

变化较大、结构不固定的扩展信息，可以考虑使用 JSON 字段。

但不能把所有 Metadata 都放进 JSON。需要高频过滤、索引和约束的字段，应该根据实际查询模式设计。

**说话人1: 问题22，为什么 PostgreSQL 可以在同一套数据库模型中关联设备、文档、Chunk、工单和向量？**

说话人1: 答，因为 PostgreSQL 同时具备关系型数据管理能力和 pgvector 提供的向量能力。

例如，Chunk 表保存 `embedding`，同时保存 `document_id` 和 `equipment_id`。

检索时可以先通过向量距离找到相关 Chunk，再关联文档表和设备表，返回文档名称、设备型号、版本和来源页码。

这样可以减少业务数据与向量数据分散在不同数据库时产生的同步和关联成本。

**说话人1: 问题23，PostgreSQL 的 ACID 事务、MVCC 与 Milvus 的一致性级别有什么区别？**

说话人1: 答，PostgreSQL 的 ACID 事务主要解决事务操作的原子性、一致性、隔离性和持久性问题。

MVCC 是 PostgreSQL 实现并发控制的重要机制，通过不同事务可见的数据版本，降低读写之间的相互阻塞。

Milvus 的一致性级别主要解决分布式环境中，写入数据之后不同查询节点能够在什么时间范围内看到这些数据的问题。

两者关注点不同：

- PostgreSQL ACID：事务执行和数据一致性。
- PostgreSQL MVCC：并发事务的数据可见性。
- Milvus Consistency Level：分布式读写的数据可见性保证。

不能把 Milvus 的 Strong 一致性简单理解为 PostgreSQL 的完整 ACID 事务。

**说话人1: 问题24，Milvus 的 Strong、Bounded Staleness、Session 和 Eventually 一致性分别适用于什么场景？**

说话人1: 答，四种一致性级别主要区别在查询对最新写入数据的可见性要求。

- Strong：查询需要满足强一致性要求，适合不能接受读取旧数据的场景。
- Bounded Staleness：允许一定范围内的数据延迟，通常在一致性和查询性能之间折中。
- Session：在会话语义下保证相关写入对后续读取的可见性。
- Eventually：允许短暂读取到旧数据，最终达到一致。

例如，用户刚上传一份维修手册，马上要求检索这份手册，如果业务要求立即可见，就需要考虑更强的一致性保证。

如果是后台批量导入，允许短时间延迟，那么可以根据业务要求选择较弱的一致性级别。

Milvus 2.6 文档中 Bounded Staleness 是默认一致性级别。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

milvus.io+1



**说话人1: 问题25，当文档、Chunk 和向量需要同时新增或删除时，使用 PostgreSQL 与使用 Milvus 分别需要考虑什么一致性问题？**

说话人1: 答，如果文档、Chunk 和向量都在 PostgreSQL 中，可以在同一个 PostgreSQL 事务中完成相关表数据的新增或删除。

但如果向量数据保存在 Milvus，文档和业务数据保存在 PostgreSQL，就需要处理跨数据库一致性问题。

例如：

```
PostgreSQL：文档写入成功
Milvus：向量写入失败
```

这时两边数据不一致。

解决方式通常是通过任务状态、Outbox、消息队列、幂等写入和补偿任务实现最终一致性。

**说话人1: 问题26，如果业务数据保存在 PostgreSQL，而向量数据保存在 Milvus，如何处理跨数据库写入失败、重复写入和数据补偿？**

说话人1: 答，可以采用可靠事件驱动或任务状态机的方式。

例如：

1. PostgreSQL 保存文档和 Chunk。
2. 同一个事务中记录待执行的向量入库事件。
3. 后台任务读取事件并调用 Milvus。
4. Milvus 写入成功后更新事件状态。
5. 失败时按照错误类型重试。
6. 多次失败后进入异常队列。
7. 通过补偿任务恢复未完成的数据。

同时，使用稳定的 `chunkId` 和版本标识作为幂等键，避免重复写入。

核心是不要假设 PostgreSQL 和 Milvus 可以参与同一个本地数据库事务。

## **四、向量索引与检索能力**

**说话人1: 问题27，pgvector 支持哪些向量距离度量？L2、Inner Product、Cosine Distance 分别代表什么？**

说话人1: 答，pgvector 常用的距离度量包括：

- L2 Distance：欧几里得距离，衡量两个向量在空间中的直线距离，运算符是 `<->`。
- Inner Product：内积，运算符是 `<#>`，pgvector 返回负内积，以便使用升序排序。
- Cosine Distance：余弦距离，运算符是 `<=>`，计算方式为 1 减去余弦相似度。

例如：

```
-- L2
ORDER BY embedding <-> '[...]'

-- Inner Product
ORDER BY embedding <#> '[...]'

-- Cosine Distance
ORDER BY embedding <=> '[...]'
```

距离越小，表示越接近。

需要根据 Embedding 模型的训练目标和向量归一化方式选择度量，不能认为三种方式可以随意互换。

**说话人1: 问题28，Milvus 支持哪些距离度量？不同度量方式对 Embedding 模型和向量归一化有什么要求？**

说话人1: 答，Milvus 支持多种距离度量，常见的包括 L2、IP 和 COSINE，不同向量类型还可能支持其他专用度量。

选择时要考虑 Embedding 模型的输出特点。

例如：

- COSINE：关注两个向量方向的相似程度。
- IP：关注向量内积，向量长度也可能影响结果。
- L2：关注向量之间的欧几里得距离。

如果模型输出的是归一化向量，Cosine 与 Inner Product 的排序关系在特定条件下可以一致。

但是不能在没有确认模型输出和数据库度量约定的情况下，直接更换度量方式。

**说话人1: 问题29，pgvector 的 HNSW 和 IVFFlat 索引分别采用什么原理？两者如何权衡检索速度、召回率、构建时间与内存？**

说话人1: 答，HNSW 是基于分层小世界图的近似最近邻索引。

它通过多层图结构建立向量之间的导航关系，检索时从高层逐步进入更细的邻近区域。

IVFFlat 则通过聚类将向量划分到多个列表中，检索时先选择部分聚类，再在这些列表中搜索。

两者主要区别：

| 对比维度 | HNSW             | IVFFlat                    |
| -------- | ---------------- | -------------------------- |
| 索引原理 | 分层近邻图       | 聚类倒排列表               |
| 构建     | 通常较耗时       | 通常较快，但依赖训练       |
| 内存     | 通常较高         | 相对可控                   |
| 查询     | 通常速度较好     | 取决于探测列表数量         |
| 调优重点 | 图构建与搜索参数 | 聚类数量与探测数量         |
| 数据要求 | 不需要聚类训练   | 需要足够有代表性的训练数据 |

HNSW 通常适合更关注检索性能和召回率的场景；IVFFlat 适合希望控制索引规模并接受聚类搜索折中的场景。

具体表现必须通过真实数据测试。

**说话人1: 问题30，Milvus 支持哪些常见向量索引？FLAT、HNSW、IVF、DiskANN 分别适合什么场景？**

说话人1: 答，常见索引可以这样理解：

- FLAT：精确搜索，不依赖近似索引，适合数据规模较小或作为召回率基准。
- HNSW：基于图的 ANN 索引，适合对查询速度和召回率有较高要求的场景。
- IVF：基于聚类的索引，通过选择部分聚类减少搜索范围。
- DiskANN：面向较大规模向量数据，利用磁盘和内存协同完成近似搜索，适合需要控制内存占用的场景。

索引是否适合，还取决于数据规模、维度、硬件、过滤条件和并发量。

不能认为索引名称相同，pgvector 和 Milvus 的实际性能就相同。

**说话人1: 问题31，为什么 HNSW 建索引时需要关注&#x20;**`m`**、**`ef_construction`**&#x20;和&#x20;**`ef_search`**&#x20;等参数？**

说话人1: 答，因为这些参数会影响 HNSW 图结构、构建成本和检索质量。

- `m`：控制图中节点连接数量的相关参数，影响图结构、内存和搜索质量。
- `ef_construction`：控制构建索引时的候选搜索范围，影响索引质量和构建时间。
- `ef_search`：控制查询时的候选搜索范围，影响查询速度和召回率。

通常：

- 增大 `m`，可能提高图的连接能力，但会增加索引资源消耗。
- 增大 `ef_construction`，通常能改善索引质量，但建索引更慢。
- 增大 `ef_search`，通常能提高召回率，但查询成本也会上升。

调优不能只追求最大参数，而应该在召回率、延迟和内存之间找到合适的平衡。

**说话人1: 问题32，为什么 IVFFlat 需要关注聚类数量、探测列表数量以及训练数据分布？**

说话人1: 答，因为 IVFFlat 先将向量聚类，再根据查询向量选择部分聚类进行搜索。

聚类数量决定向量被划分成多少个列表，探测列表数量决定查询时访问多少个列表。

如果聚类数量过少，每个列表中的向量太多，查询效率可能下降。

如果聚类数量过多，索引构建和维护可能变得复杂，而且查询时只探测少量列表容易漏掉真正相关的数据。

训练数据分布也很重要。如果训练样本不能代表实际向量分布，聚类中心可能不合理，导致检索质量下降。

因此，IVFFlat 不仅要关注参数，还要关注数据分布和数据量。

**说话人1: 问题33，pgvector 的 HNSW 与 Milvus 的 HNSW 是否意味着两者性能相同？还需要考虑哪些实现和运行环境差异？**

说话人1: 答，不相同。

HNSW 只是索引算法家族，不代表具体实现、执行环境和系统架构完全一样。

还需要比较：

- HNSW 实现细节。
- 索引参数和默认值。
- 向量数据类型。
- 内存管理。
- 并发执行模型。
- Metadata Filter 的执行方式。
- 数据加载和缓存机制。
- 网络通信成本。
- 硬件和存储配置。
- 数据库版本和索引实现版本。

例如，pgvector 的 HNSW 运行在 PostgreSQL 内部，需要与关系查询、事务和其他数据库工作负载共享资源。

Milvus 则可以在独立的查询节点上分配检索资源。

所以，即使都使用 HNSW，也必须进行同条件基准测试。

**说话人1: 问题34，什么是向量检索召回率（Recall）？如何在 pgvector 和 Milvus 之间进行公平比较？**

说话人1: 答，Recall 用来衡量系统找回了多少应该找到的相关结果。

对于 ANN 检索，可以通过精确最近邻搜索得到 Ground Truth，再比较 ANN 返回的 TopK。

例如：

```
精确搜索 Top10：A B C D E F G H I J
ANN 搜索 Top10：A B C D E K L M N O
```

两者交集有 A、B、C、D、E 五个结果，因此：

\\[ Recall\@10 = \frac{5}{10} = 0.5 \\]

比较 pgvector 和 Milvus 时，应该统一：

- Embedding 数据集。
- 查询向量。
- 距离度量。
- TopK。
- Metadata Filter。
- Ground Truth。
- 硬件资源。
- 并发和测试方法。

否则比较出来的结果不具有充分可比性。

**说话人1: 问题35，为什么不能只比较平均检索延迟？P95、P99 延迟、吞吐量和资源消耗分别说明什么？**

说话人1: 答，平均延迟只能说明整体平均水平，不能反映少数请求的极端慢查询。

例如：

- P50：一半请求的延迟不超过该值。
- P95：95% 请求的延迟不超过该值。
- P99：99% 请求的延迟不超过该值。
- QPS：每秒处理的查询请求数量。
- CPU：检索计算的处理器消耗。
- 内存：向量数据、索引和缓存的资源占用。
- 磁盘 IO：数据加载、索引读取和持久化产生的开销。

生产系统尤其需要关注 P95 和 P99，因为少量慢请求可能明显影响用户体验。

此外，还要同时观察召回率。不能通过牺牲大量召回率换取更低延迟，然后声称检索性能更好。

**说话人1: 问题36，pgvector 在近似向量检索时，为什么可能出现过滤后结果数量不足的问题？**

说话人1: 答，因为在近似索引检索中，向量候选搜索与 SQL 过滤条件的执行顺序会影响最终结果数量。

例如，HNSW 先扫描了一批候选向量，然后通过设备型号条件过滤。

假设扫描了 40 条候选记录，只有 4 条符合当前设备型号条件，那么最终返回结果可能不足 TopK。

这并不代表数据库中只有 4 条相关数据，而是候选扫描范围不够大。

pgvector 0.8.0 开始提供 Iterative Index Scans，可以在结果不足时继续扫描索引，直到满足数量要求或达到扫描上限。

此外，还可以根据过滤条件考虑 Partial Index 和 Partitioning。

[image](https://www.google.com/s2/favicons?domain=https://github.com\&sz=32)

GitHub+1



**说话人1: 问题37，pgvector 的 Iterative Index Scans、Partial Index 和 Partitioning 分别解决什么检索问题？**

说话人1: 答，三种方式解决的不是同一个问题。

- Iterative Index Scans：当近似检索经过过滤后结果不足时，继续扫描更多候选数据。
- Partial Index：针对特定过滤条件建立部分索引，减少索引中无关数据。
- Partitioning：按照设备型号、租户或其他字段拆分数据，让检索范围更有针对性。

例如，设备知识库有大量设备型号，某个型号只占总数据的一小部分，可以考虑针对该型号设计部分索引或分区。

但是，分区数量、数据分布和查询模式都需要考虑，不能每一个设备型号都机械地创建独立分区。

**说话人1: 问题38，Milvus 的标量过滤、Partition、Partition Key 和多向量检索分别有什么作用？**

说话人1: 答，Milvus 提供多种数据组织和过滤方式。

- 标量过滤：根据设备型号、文档版本、审核状态等字段筛选候选数据。
- Partition：将 Collection 中的数据划分为不同逻辑分区，便于管理和缩小检索范围。
- Partition Key：根据指定字段自动将数据分配到相应分区，支持特定场景下的查询路由和数据组织。
- 多向量检索：一个 Entity 可以包含多个向量字段，支持不同模态或不同语义空间的检索。

例如，一个设备知识 Chunk 可能同时保存文本 Embedding 和图片 Embedding，分别用于维修说明检索和设备图片检索。

但多向量检索的具体查询策略和结果融合方式，仍然需要根据业务需求设计。

## **五、RAG 与制造设备知识库场景**

**说话人1: 问题39，在 RAG 中，为什么向量检索不能完全替代关键词检索和结构化 SQL 查询？**

说话人1: 答，因为三种查询方式解决的问题不同。

向量检索擅长语义相似度搜索，例如用户描述“电机运行时异常振动”，系统找到相关维修知识。

关键词检索擅长精确匹配，例如故障代码 `E001`、设备型号 `M-200`。

SQL 查询擅长结构化条件查询，例如查询某型号设备当前有效的维修手册版本。

如果只依赖向量检索，可能无法准确处理故障代码、型号和版本等精确条件。

因此，生产级 RAG 通常需要组合语义检索、关键词检索和结构化过滤。

**说话人1: 问题40，设备型号、故障编码、文档版本、设备类别等元数据，应该如何参与向量检索过滤？**

说话人1: 答，应该将它们作为 Chunk 的 Metadata，并根据用户问题和后端业务权限构造过滤条件。

例如：

```
{
  "equipmentModel": "M-200",
  "faultCode": "E001",
  "documentVersion": "V3",
  "documentType": "MAINTENANCE_MANUAL",
  "approvalStatus": "APPROVED",
  "isCurrent": true
}
```

用户查询时，先确定目标设备型号和故障代码，再通过 Metadata Filter 限定候选知识范围。

这里的租户权限、用户权限和文档审核状态必须由后端控制，不能完全相信用户输入或模型生成的过滤条件。

**说话人1: 问题41，对于“查询某型号设备的 E001 故障处理方法”，为什么需要同时考虑语义相似度和结构化条件过滤？**

说话人1: 答，因为这个问题同时包含精确业务条件和语义需求。

“某型号设备”是设备范围，“E001”是精确故障编码，“故障处理方法”是需要通过语义理解找到的内容。

正确的检索策略应该是：

1. 通过设备型号过滤。
2. 通过故障代码过滤。
3. 通过审核状态和版本过滤。
4. 在符合条件的 Chunk 中执行向量检索。
5. 必要时结合 BM25 关键词检索。
6. 对候选结果进行 Rerank。

这样可以避免找到其他型号设备的 E001 故障知识，或者找到语义相似但故障代码不同的维修内容。

**说话人1: 问题42，PostgreSQL 的全文检索&#x20;**`tsvector`**、**`tsquery`**&#x20;与 pgvector 的向量检索有什么区别？**

说话人1: 答，PostgreSQL 全文检索主要处理词语匹配和文本检索。

- `tsvector`：将文本转换为适合全文检索的词项表示。
- `tsquery`：表示用户的全文检索查询条件。

例如，维修手册中包含“电机过热保护”，可以使用全文检索寻找包含相关词项的文本。

pgvector 则将文本转换为 Embedding 向量，通过距离计算寻找语义相近的内容。

区别是：

- 全文检索：关注词项及其匹配关系。
- 向量检索：关注文本在向量空间中的语义相似程度。

两者可以互补。

**说话人1: 问题43，PostgreSQL 如何组合全文检索和向量检索，实现关键词与语义混合检索？**

说话人1: 答，可以分别执行全文检索和向量检索，再通过统一的排序策略合并结果。

例如：

```
用户问题
   ├── PostgreSQL Full Text Search
   │       └── BM25-like / 词项相关性结果
   │
   └── pgvector
           └── 向量相似度结果
                 ↓
              结果融合
                 ↓
               Rerank
                 ↓
              TopK Chunk
```

PostgreSQL 全文检索可以通过 `tsvector`、`tsquery` 和 GIN 索引实现。

向量检索则通过 pgvector 的向量类型和索引实现。

两路结果可以使用 RRF 等方法融合，再通过 Reranker 重新排序。

需要注意，PostgreSQL 原生全文检索的排序函数不应直接等同于标准 BM25。若需要标准 BM25，需要采用相应扩展或独立检索实现。

**说话人1: 问题44，Milvus 的 Dense Vector、Sparse Vector、BM25 和 Hybrid Search 分别能解决什么问题？**

说话人1: 答，Dense Vector 是稠密向量，通常由 Embedding 模型生成，适合语义相似度检索。

Sparse Vector 是稀疏向量，只有部分维度具有非零值，适合表达词项特征和稀疏检索信息。

BM25 是经典的词项相关性检索算法，关注查询词和文档词项之间的匹配关系。

Hybrid Search 则可以组合稠密向量和稀疏向量等多路检索结果。

例如，用户查询“E001 电机过热”，Dense Vector 可以找到语义相关的维修说明，Sparse Vector 或 BM25 可以强化 `E001` 这样的精确关键词匹配。

最后再通过结果融合得到更完整的候选集。

**说话人1: 问题45，如果设备故障知识库存在大量型号、故障编码和文档版本过滤条件，应该如何测试两种方案的检索准确率？**

说话人1: 答，应该建立具有真实业务分布的测试数据集，而不是只测试没有过滤条件的纯向量查询。

测试集至少包含：

- 不同设备型号。
- 不同故障代码。
- 多个文档版本。
- 不同文档类型。
- 不同租户。
- 已审核和未审核内容。
- 语义相似但设备型号不同的干扰数据。

然后为每个问题标注正确的文档和 Chunk。

对 pgvector 和 Milvus 使用相同的 Embedding、查询、过滤条件和 TopK，计算 Recall\@K、Precision\@K、MRR、NDCG，并记录 P95、P99 延迟和资源消耗。

还需要专门测试过滤条件组合下是否出现候选结果不足的问题。

**说话人1: 问题46，当检索结果需要返回文档名称、来源页码、版本号和 Chunk 内容时，哪种数据组织方式更有利于业务关联与来源追溯？**

说话人1: 答，如果业务数据已经主要存储在 PostgreSQL，那么 PostgreSQL + pgvector 通常更容易进行关系关联和来源追溯。

可以通过 `chunk_id` 关联 Chunk 表，通过 `document_id` 关联文档表，再关联设备表。

Milvus 也可以将文档名称、页码和版本等字段作为标量或 JSON Metadata 保存。

但复杂业务关系和多表 JOIN 不是 Milvus 的主要设计目标，通常仍需要业务数据库参与。

因此，当前项目在关系数据管理方面使用 PostgreSQL 会更自然。

## **六、部署、运维与成本**

**说话人1: 问题47，PostgreSQL + pgvector 与 Milvus Standalone 的部署依赖分别有哪些？**

说话人1: 答，PostgreSQL + pgvector 通常只需要运行 PostgreSQL，并安装与 PostgreSQL 版本兼容的 pgvector 扩展。

主要管理：

- PostgreSQL 服务。
- pgvector 扩展。
- 数据目录和磁盘空间。
- 数据库连接及权限。
- 备份和监控。

Milvus Standalone 则是独立的向量数据库服务。虽然多个核心组件可以封装在一个 Docker 镜像中，但仍然需要管理 Milvus 自身的数据目录、服务端口、配置和持久化。

Milvus 2.6 的部署模式还涉及 WAL、元数据存储和对象存储等依赖，具体是否需要独立外部服务取决于部署配置。

所以，Standalone 简化了分布式部署，但不意味着它没有独立的服务运维成本。

[image](https://www.google.com/s2/favicons?domain=https://milvus.io\&sz=32)

milvus.io+1



**说话人1: 问题48，Milvus Distributed 为什么通常需要 Kubernetes 或其他集群运维能力？**

说话人1: 答，因为 Distributed 模式需要管理多个独立运行的组件。

例如：

- Proxy。
- Coordinator。
- Streaming Node。
- Query Node。
- Data Node。
- 元数据存储。
- 对象存储。
- WAL 存储。

这些组件需要服务发现、网络通信、资源调度、健康检查、故障恢复和扩缩容。

Kubernetes 可以帮助管理容器、服务发现、资源调度和故障恢复。

但 Kubernetes 并不是理解和运行 Milvus 分布式架构的全部条件，还需要具备存储、网络、监控和数据库运维能力。

**说话人1: 问题49，Milvus Standalone 虽然能够单机部署，为什么仍然需要单独管理向量数据库服务？**

说话人1: 答，因为它仍然是一个独立于 PostgreSQL 的数据库服务。

Java 应用需要单独连接 Milvus，单独管理客户端依赖、连接参数、服务健康状态和异常处理。

此外，还需要考虑：

- Milvus 数据目录持久化。
- Collection 和索引管理。
- 数据备份与恢复。
- 服务升级。
- 资源监控。
- 连接和访问权限。

所以，单机部署只是降低了集群管理复杂度，并没有消除数据库服务本身的维护工作。

**说话人1: 问题50，Milvus Collection 在执行检索前为什么需要满足索引和 Load 等条件？这对内存规划有什么影响？**

说话人1: 答，因为 Milvus 需要准备好相关数据和索引，才能提供对应的检索服务。

创建 Collection 之后，通常还需要根据 Schema 和索引配置建立索引，并将 Collection 或相关数据加载到查询服务中。

查询节点需要为执行检索准备数据、索引及必要的缓存资源。

如果向量规模较大、索引较复杂，加载过程可能消耗较多内存。

因此，不能只按照向量数据文件大小估算内存，还要考虑索引结构、标量字段、缓存、并发查询和节点资源分配。

Milvus 2.6 的 Tiered Storage 等能力也会影响数据加载策略，实际内存需求需要根据部署版本和配置测试。

**说话人1: 问题51，PostgreSQL 的备份、WAL、PITR 与 Milvus 的数据备份恢复机制有什么区别？**

说话人1: 答，PostgreSQL 有成熟的关系数据库备份和恢复体系。

- 逻辑备份：通过 `pg_dump` 等方式备份数据库对象和数据。
- 物理备份：备份数据库文件。
- WAL：记录数据库变更。
- PITR：Point-in-Time Recovery，可以通过基础备份和 WAL 将数据库恢复到指定时间点。

Milvus 则需要考虑 Collection 数据、索引、元数据、对象存储和 WAL 等组成部分的备份与恢复。

Milvus Backup 等工具可以帮助完成相应的数据备份和恢复操作，但具体恢复能力取决于部署版本、备份工具和存储配置。

两者不能简单比较为谁更安全，而应该比较备份范围、恢复时间、恢复点目标和实际演练结果。

**说话人1: 问题52，如果数据库发生故障，两种方案分别如何恢复数据、重建索引并验证检索可用性？**

说话人1: 答，如果 PostgreSQL 发生故障，应该根据故障类型使用备份、WAL 或 PITR 恢复数据库。

恢复之后，检查：

1. 文档和 Chunk 数据是否完整。
2. 向量字段是否完整。
3. pgvector 扩展是否正常。
4. HNSW 或 IVFFlat 索引是否存在并可用。
5. 典型查询是否正常。
6. 检索召回率和延迟是否符合要求。

如果 Milvus 发生故障，则需要按照部署架构恢复元数据、对象存储和 WAL 相关数据，并恢复 Collection、索引及查询服务。

之后同样需要验证数据完整性、索引状态、查询能力和检索质量。

两套方案都不能只以服务启动成功作为恢复完成标准，必须进行业务级检索验证。

**说话人1: 问题53，PostgreSQL 与 Milvus 在监控、日志、告警、容量规划和版本升级方面，需要分别关注哪些指标？**

说话人1: 答，PostgreSQL 重点关注：

- CPU、内存和磁盘空间。
- 数据库连接数。
- 慢 SQL。
- 查询延迟。
- 锁等待和事务状态。
- WAL 增长。
- 缓存命中情况。
- 索引大小和膨胀。
- 数据库备份状态。

Milvus 除了机器资源，还需要关注：

- Query Node 的查询延迟和资源消耗。
- Streaming Node 的写入和实时处理情况。
- Data Node 的索引构建和 Compaction。
- Collection 加载状态。
- 查询错误率和吞吐量。
- 对象存储访问。
- WAL 积压和消费状态。
- 集群组件健康情况。

版本升级方面，PostgreSQL 需要关注数据库主版本、扩展兼容性和升级方式；Milvus 还需要关注各组件兼容性、数据格式和集群升级流程。

**说话人1: 问题54，如果同时运行 PostgreSQL、Milvus、对象存储和其他基础设施服务，会增加哪些 CPU、内存、磁盘和网络成本？**

说话人1: 答，主要增加四类资源成本。

第一，CPU。PostgreSQL 需要执行业务 SQL 和向量查询，Milvus 需要执行向量检索、索引构建和数据处理。

第二，内存。两套数据库都需要缓存和索引相关内存，Milvus 还需要考虑查询节点加载的数据和索引。

第三，磁盘和存储。需要保存 PostgreSQL 数据、Milvus 持久化数据、索引文件、日志和备份。

第四，网络。Java 应用需要访问两套数据库，Milvus 还可能需要与对象存储、元数据服务和 WAL 服务通信。

对于早期项目，这些额外资源不仅是服务器费用，还包括监控、备份、升级、故障处理和维护人员的时间成本。

**说话人1: 问题55，在没有专职 DBA 或运维人员的早期项目中，如何评估两种方案的实际维护成本，而不是只计算部署命令的数量？**

说话人1: 答，应该计算整个生命周期的维护成本，而不是只看第一次部署花费多少时间。

我会重点比较：

1. 部署和初始化难度。
2. 日常监控工作量。
3. 备份和恢复复杂度。
4. 故障定位难度。
5. 版本升级风险。
6. 数据同步和一致性处理。
7. 资源占用。
8. 团队是否具备相应技术能力。

例如，当前已经使用 PostgreSQL 管理业务数据，再增加 pgvector，主要是增加扩展配置、向量索引和检索调优工作。

如果引入 Milvus，则需要额外维护独立数据库服务，并处理 PostgreSQL 与 Milvus 的数据同步和故障补偿。

对于早期项目，后者可能带来更高的综合维护成本。

## **七、Spring AI 与 Java 工程集成**

**说话人1: 问题56，Spring AI 的&#x20;**`VectorStore`**&#x20;抽象接口解决了什么问题？它能否完全屏蔽底层数据库差异？**

说话人1: 答，`VectorStore` 是 Spring AI 提供的向量存储抽象接口，目的是统一应用层对向量数据的基本操作。

常见能力包括：

- 添加 Document。
- 删除 Document。
- 相似度检索。
- 设置检索参数。
- 使用 Metadata Filter。

这样，业务代码不必直接依赖某一个数据库的原生客户端。

但它不能完全屏蔽底层差异。

例如，不同数据库的索引类型、过滤表达式、距离度量、一致性和原生高级功能仍然不同。

所以，`VectorStore` 能够降低应用层耦合，但不能保证更换数据库时完全不修改代码和配置。

**说话人1: 问题57，Spring AI 1.1.8 中，**`PgVectorStore`**&#x20;和&#x20;**`MilvusVectorStore`**&#x20;分别如何与&#x20;**`EmbeddingModel`**&#x20;协作？**

说话人1: 答，两者都可以与 Spring AI 的 `EmbeddingModel` 配合。

基本流程是：

```
Document
   ↓
VectorStore
   ↓
EmbeddingModel
   ↓
Embedding
   ↓
底层向量数据库
```

添加文档时，VectorStore 会使用 EmbeddingModel 将文本转换为向量，再保存到对应数据库。

查询时，也会将用户查询转换为向量，再调用底层数据库进行相似度检索。

区别在于：

- `PgVectorStore` 将向量存储和检索操作交给 PostgreSQL/pgvector。
- `MilvusVectorStore` 将向量存储和检索操作交给 Milvus。

实际使用时，需要确保 EmbeddingModel 的输出维度与数据库向量字段匹配。

**说话人1: 问题58，两种 VectorStore 在文档新增、删除、相似度检索和 Metadata Filter 方面有哪些共同能力与差异？**

说话人1: 答，两者都实现了 Spring AI 的 VectorStore 抽象，可以完成基本的 Document 新增、删除和相似度搜索。

但是，底层能力并不完全相同。

例如：

- 新增文档：两者都能保存文本及向量，但底层写入机制不同。
- 删除文档：需要根据各自实现支持的标识和过滤能力执行。
- 相似度检索：距离计算和索引实现不同。
- Metadata Filter：表达式语法和支持的字段操作可能不同。
- 高级检索：Milvus 的部分原生向量能力不一定通过统一接口全部暴露。

所以，不能只看两者都实现了 `VectorStore`，就认为它们具有完全一致的功能覆盖。

**说话人1: 问题59，使用 Spring AI 的统一&#x20;**`VectorStore`**&#x20;接口后，为什么更换数据库仍可能需要修改配置、索引策略、Metadata 设计和检索逻辑？**

说话人1: 答，因为抽象接口统一的是基础操作，不是底层数据库的全部行为。

例如：

- PostgreSQL 使用 HNSW 或 IVFFlat。
- Milvus 可以使用多种专用索引。
- PostgreSQL 的 Metadata Filter 使用 SQL 和相应表达式。
- Milvus 使用自己的过滤表达式。
- 两者的数据模型、索引构建和加载方式不同。
- 两者的一致性和备份机制不同。

因此，更换数据库后，可能需要重新设计 Schema、索引参数、过滤策略、部署配置和性能测试。

**说话人1: 问题60，Spring AI 的&#x20;**`PgVectorStore`**&#x20;如何利用&#x20;**`JdbcTemplate`**&#x20;访问 PostgreSQL？Milvus 如何通过原生客户端访问 Milvus 服务？**

说话人1: 答，`PgVectorStore` 基于 Spring 的 JDBC 数据访问体系与 PostgreSQL 交互，可以通过 `JdbcTemplate` 执行建表、插入、删除和相似度查询等数据库操作。

Milvus 则通过 Milvus 对应的 Java 客户端与 Milvus 服务通信，执行 Collection、索引、数据插入和向量搜索等操作。

两者的主要区别是：

- PgVectorStore：依赖 JDBC 和 PostgreSQL SQL 执行机制。
- MilvusVectorStore：依赖 Milvus 原生客户端和服务端 API。

因此，连接配置、异常类型、连接管理和部分高级功能的使用方式都不一样。

**说话人1: 问题61，为什么在选择向量数据库时，还需要考察 Spring AI 对应实现的功能覆盖、版本兼容性和原生客户端扩展能力？**

说话人1: 答，因为数据库本身支持某项能力，不代表 Spring AI 对应的 VectorStore 实现一定已经封装了这项能力。

例如，某个数据库支持特殊索引、复杂过滤或多向量搜索，但 Spring AI 的统一接口可能只暴露了基础功能。

所以要检查：

1. Spring AI 1.1.8 对应实现是否可用。
2. Starter 和依赖版本是否兼容。
3. Embedding 维度是否匹配。
4. Metadata Filter 是否满足业务需求。
5. 原生客户端是否支持所需高级能力。
6. 是否需要通过自定义组件扩展。

不能只看数据库官网功能列表就判断 Java 工程能够直接使用。

**说话人1: 问题62，如果未来需要将 pgvector 迁移到 Milvus，哪些代码可以通过&#x20;**`VectorStore`**&#x20;接口保持不变？哪些数据结构、SQL、索引和运维配置必须重新设计？**

说话人1: 答，首先，使用 `VectorStore` 抽象的基础业务代码有机会保持不变。

例如：

- Document 构建。
- 基础文档添加和删除调用。
- 基础相似度检索调用。
- 部分通用 Metadata 处理逻辑。

但以下内容通常需要重新设计：

- PostgreSQL 表结构和 SQL。
- Milvus Collection Schema。
- 向量索引和参数。
- Metadata Filter 表达式。
- 数据导入和迁移脚本。
- 删除和版本管理机制。
- 一致性和补偿任务。
- 备份恢复方案。
- 监控和运维配置。

迁移时还需要将 PostgreSQL 中已有的 Chunk 和向量数据导出，转换为 Milvus 对应的数据结构，再进行批量导入和检索验证。

因此，`VectorStore` 可以降低迁移成本，但不能消除迁移成本。

## **八、选型决策：为何当前不选择 Milvus**

**说话人1: 问题63，当前制造设备故障知识库项目的文档数量、Chunk 数量、向量维度、数据增长率和并发检索量，应该如何估算？**

说话人1: 答，不能凭感觉估算，应该从业务数据和 RAG 处理流程入手。

首先估算文档数量：

```
文档总量 = 当前文档数量 + 预计新增文档数量
```

其次估算 Chunk 数量：

```
Chunk 总量 = 文档总量 × 平均每份文档生成的 Chunk 数
```

例如：

- 1000 份维修手册。
- 每份平均 100 个 Chunk。
- 总计约 10 万个 Chunk。

再估算向量存储空间。

对于 pgvector 的 `vector(1536)`，单个向量的数据主体大约是：

\\[ 1536 \times 4 = 6144\text{ bytes} \\]

10 万个向量仅向量数据主体就约为 614.4 MB，尚未计算表结构、索引、Metadata、页开销和备份空间。

然后估算：

1. 每月新增文档数量。
2. 每月新增 Chunk 数量。
3. Embedding 模型的维度。
4. 平均查询 QPS。
5. 峰值并发请求数。
6. P95、P99 延迟目标。
7. 数据保留期限和版本数量。

最后根据这些数据进行容量规划和性能测试，而不是只看当前文档数量。

**说话人1: 问题64，在什么数据规模、查询吞吐量、并发量和延迟要求下，PostgreSQL + pgvector 可能不再满足项目需求？**

说话人1: 答，没有一个适用于所有项目的固定向量数量阈值。

例如，100 万条 768 维向量，在合理硬件和索引配置下可能运行得很好；而 10 万条高维向量，如果过滤条件复杂、并发很高，也可能出现性能问题。

应该观察：

- P95、P99 延迟是否持续超标。
- QPS 是否达到数据库处理能力上限。
- CPU 和内存是否长期处于高负载。
- HNSW 索引是否占用过多内存。
- 过滤后 Recall\@K 是否明显下降。
- 写入和索引构建是否影响在线查询。
- 业务 SQL 是否与向量检索争抢资源。

当 PostgreSQL 经过索引优化、查询优化、资源扩容和负载隔离后，仍然不能满足业务指标，就应该评估独立向量数据库。

所以，迁移依据应该是可验证的性能瓶颈，而不是简单的向量数量。

**说话人1: 问题65，为什么当前阶段可以优先选择 PostgreSQL + pgvector，而不是直接引入 Milvus？需要具备哪些可验证的业务和工程依据？**

说话人1: 答，当前优先选择 PostgreSQL + pgvector，主要是因为它与项目现有的数据架构更加匹配。

第一，业务数据已经使用 PostgreSQL 管理，包括设备、文档、Chunk 和工单。

第二，当前 RAG 项目主要需要基础向量检索、Metadata Filter 和混合检索，并没有已经验证的超大规模向量检索需求。

第三，pgvector 可以使用 SQL、事务和关系查询能力，减少业务数据与向量数据之间的同步问题。

第四，部署和运维相对简单，不需要额外引入一套独立的向量数据库服务。

第五，当前阶段更应该把精力放在 RAG 全链路工程能力上，例如文档治理、Chunk 质量、Embedding、检索评估和引用追溯。

但是，这个选型必须通过测试验证。

我至少会建立一套真实数据集，测试向量检索召回率、P95 延迟、过滤性能、并发能力和资源占用。

如果这些指标能够满足当前需求，就没有必要提前引入更复杂的基础设施。

**说话人1: 问题66，当前项目已经使用 PostgreSQL 管理业务数据时，继续使用 pgvector 能减少哪些数据同步、服务治理和运维复杂度？**

说话人1: 答，最大的优势是减少跨数据库的数据同步和一致性问题。

例如，一份维修手册入库时，需要保存：

- 文档信息。
- Chunk 内容。
- Embedding 向量。
- 设备型号。
- 文档版本。
- 审核状态。

如果这些数据都在 PostgreSQL 中，可以在同一个数据库体系内管理。

在合理的数据模型设计下，文档、Chunk 和向量记录可以通过事务实现一致性操作。

同时，能够复用：

- 数据库连接管理。
- 事务机制。
- SQL 查询。
- 备份恢复。
- 权限控制。
- 监控体系。
- 数据迁移工具。

如果额外使用 Milvus，就需要处理 PostgreSQL 与 Milvus 之间的数据同步、失败补偿、重复写入和双系统监控。

**说话人1: 问题67，不选择 Milvus 是否意味着 Milvus 性能不足？如何区分产品能力不足、项目需求不匹配和团队运维成本不匹配？**

说话人1: 答，不意味着 Milvus 性能不足。

这三个概念应该分开理解。

第一，产品能力不足。

指数据库本身的检索能力、数据模型、索引类型或扩展能力无法满足需求。

第二，项目需求不匹配。

指产品具备很强的能力，但当前业务没有相应需求，引入之后不能产生足够的实际价值。

第三，团队运维成本不匹配。

指团队缺少相应的数据库运维、分布式系统、监控和故障处理能力，引入之后增加了系统复杂度。

当前项目不选择 Milvus，主要是后两种原因，而不是认为 Milvus 性能不足。

如果未来业务规模和检索负载发生变化，Milvus 完全可能成为更合适的选择。

**说话人1: 问题68，如果未来出现向量规模快速增长、独立扩展检索资源、超高并发或复杂多向量混合检索需求，应该如何判断是否迁移到 Milvus？**

说话人1: 答，应该先确认当前 PostgreSQL 的瓶颈，再判断 Milvus 是否能够解决这些问题。

例如：

1. 向量数据增长导致索引和内存资源压力明显增加。
2. 向量检索与业务 SQL 产生严重资源竞争。
3. 查询吞吐量已经超过当前架构的承载能力。
4. 需要独立扩展向量查询和数据写入资源。
5. 需要更丰富的向量索引和多向量检索能力。
6. 经过优化后仍然无法满足 P95、P99 延迟目标。

确认这些需求后，可以建立 Milvus PoC，使用相同数据集进行性能测试。

如果 Milvus 能够在召回率、延迟、吞吐量和资源成本方面明显改善，并且团队具备运维能力，就可以考虑迁移。

迁移不是简单地替换数据库连接，而是一次数据架构调整。

**说话人1: 问题69，如何设计一套公平的 PostgreSQL + pgvector 与 Milvus 对比测试？需要统一哪些硬件、向量数据、索引参数、过滤条件、并发和测试指标？**

说话人1: 答，应该建立统一的 Benchmark 测试方案。

第一，统一硬件环境。

例如，使用相同 CPU、内存、磁盘和网络条件，避免因为硬件差异导致测试结果失真。

第二，统一数据。

使用完全相同的文档 Chunk、Embedding 向量、向量维度和 Metadata。

第三，统一检索条件。

包括：

- 查询向量。
- 距离度量。
- TopK。
- Metadata Filter。
- 租户隔离条件。
- 设备型号过滤。
- 文档版本过滤。

第四，统一索引调优。

分别对 pgvector 和 Milvus 进行合理调优，不要求参数完全相同，但应该在相同的资源预算和召回率目标下比较。

第五，统一负载。

设计不同并发等级，例如：

```
并发：1、10、50、100
```

同时测试稳定负载、突发负载和持续高负载。

第六，统一评估指标。

| 指标             | 目的                   |
| ---------------- | ---------------------- |
| Recall\@K        | 衡量检索召回质量       |
| Precision\@K     | 衡量检索结果准确程度   |
| MRR              | 衡量首个相关结果的排名 |
| NDCG             | 衡量排序质量           |
| P50              | 典型请求延迟           |
| P95              | 高分位请求延迟         |
| P99              | 极端慢请求延迟         |
| QPS              | 查询吞吐量             |
| CPU              | 计算资源消耗           |
| Memory           | 内存资源消耗           |
| Disk             | 数据与索引存储成本     |
| Write Throughput | 数据写入能力           |

最后，还应该测试数据新增、更新、删除、版本切换和故障恢复。

这样才能得到真实的选型依据，而不是只比较某一条向量查询的耗时。

**说话人1: 问题70，如果面试官问“为什么你的 RAG 项目不选择 Milvus，而选择 PostgreSQL + pgvector”，如何从业务需求、数据架构、检索能力、部署成本、团队能力和未来扩展性六个方面给出有证据的回答？**

说话人1: 答，我当前的制造设备故障知识库项目选择 PostgreSQL + pgvector，而没有直接引入 Milvus，主要是综合考虑了六个方面。

第一，业务需求。

当前项目主要面向制造设备故障知识库和工单助手，需要文档向量检索、设备型号过滤、故障代码查询、文档版本管理和来源追溯。目前没有已经验证的超大规模向量检索或超高并发需求。

第二，数据架构。

项目已经使用 PostgreSQL 管理业务数据，包括设备、文档、Chunk 和工单。使用 pgvector 可以在同一个数据库体系中管理关系数据和向量数据，减少跨数据库同步及一致性处理。

第三，检索能力。

pgvector 支持精确向量搜索，也支持 HNSW、IVFFlat 等近似索引，并且可以结合 PostgreSQL 全文检索和 SQL 条件过滤。对于当前的设备型号、故障代码和文档版本过滤需求，能够提供所需的基础能力。

第四，部署成本。

PostgreSQL + pgvector 可以复用现有数据库基础设施，不需要额外部署和维护一套向量数据库服务。这样可以降低早期项目的部署、监控、备份和故障处理成本。

第五，团队能力。

当前阶段更需要把精力投入到 Java AI 工程、RAG 数据处理、Embedding、检索优化和企业级质量治理上。过早引入分布式向量数据库，会增加基础设施维护负担。

第六，未来扩展性。

我并不是排斥 Milvus，而是暂时没有必要提前引入。如果未来向量规模、查询并发和延迟要求持续增长，或者 PostgreSQL 在合理优化后仍然无法满足业务指标，我会通过统一 Benchmark 对比两种方案，再决定是否迁移。

最后，我会通过真实数据集验证 Recall\@K、Precision\@K、P95、P99、QPS、CPU 和内存等指标，而不是单纯根据产品定位做判断。

所以，我当前选择 PostgreSQL + pgvector 的原因不是 Milvus 性能不足，而是它更符合现阶段的业务需求、数据架构和团队维护能力。

说话人1: 全部回答完毕。

## **九、参考资料**

以下资料优先采用官方文档。需要特别注意：Spring AI 的 Starter 名称、配置属性和自动配置行为可能随版本变化，项目实际使用时应以 **Spring AI 1.1.8 对应依赖和 API** 为准，不要直接复制其他大版本的配置。

### **1. PostgreSQL 与 pgvector**

1. pgvector: Open-source vector similarity search for Postgres | pgvector：PostgreSQL 开源向量相似度搜索扩展：[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector?utm_source=chatgpt.com)
2. PostgreSQL 18 Documentation | PostgreSQL 18 官方文档：[https://www.postgresql.org/docs/18/](https://www.postgresql.org/docs/18/?utm_source=chatgpt.com)
3. Chapter 12. Full Text Search | 第 12 章：全文检索：[https://www.postgresql.org/docs/18/textsearch.html](https://www.postgresql.org/docs/18/textsearch.html?utm_source=chatgpt.com)
4. 13.1. Introduction | 13.1 多版本并发控制简介：[https://www.postgresql.org/docs/18/mvcc-intro.html](https://www.postgresql.org/docs/18/mvcc-intro.html?utm_source=chatgpt.com)
5. Continuous Archiving and Point-in-Time Recovery (PITR) | 连续归档与时间点恢复：[https://www.postgresql.org/docs/18/continuous-archiving.html](https://www.postgresql.org/docs/18/continuous-archiving.html?utm_source=chatgpt.com)

### **2. Milvus**

6. What is Milvus | 什么是 Milvus：[https://milvus.io/docs/v2.6.x/overview.md](https://milvus.io/docs/v2.6.x/overview.md?utm_source=chatgpt.com)
7. Overview of Milvus Deployment Options | Milvus 部署模式概览：[https://milvus.io/docs/install-overview.md](https://milvus.io/docs/install-overview.md?utm_source=chatgpt.com)
8. Main Components | Milvus 核心组件：[https://milvus.io/docs/v2.6.x/main_components.md](https://milvus.io/docs/v2.6.x/main_components.md?utm_source=chatgpt.com)
9. Architecture Overview | Milvus 架构概览：[https://milvus.io/docs/v2.6.x/architecture_overview.md](https://milvus.io/docs/v2.6.x/architecture_overview.md?utm_source=chatgpt.com)
10. Consistency | Milvus 一致性机制：[https://milvus.io/docs/v2.6.x/consistency.md](https://milvus.io/docs/v2.6.x/consistency.md?utm_source=chatgpt.com)
11. Implement Multi-tenancy | Milvus 多租户实现：[https://milvus.io/docs/v2.6.x/multi_tenancy.md](https://milvus.io/docs/v2.6.x/multi_tenancy.md?utm_source=chatgpt.com)
12. Milvus Backup | Milvus 数据备份与恢复：[https://milvus.io/docs/v2.6.x/milvus_backup_overview.md](https://milvus.io/docs/v2.6.x/milvus_backup_overview.md?utm_source=chatgpt.com)

### **3. Spring AI**

13. PGvector | Spring AI PostgreSQL/pgvector 向量存储：[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/pgvector.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/pgvector.html?utm_source=chatgpt.com)
14. Milvus | Spring AI Milvus 向量存储：[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/milvus.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/milvus.html?utm_source=chatgpt.com)
15. Vector Databases | Spring AI 向量数据库抽象与集成：[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html?utm_source=chatgpt.com)
16. Spring AI 1.1.x API | Spring AI 1.1.x API 文档：[https://docs.spring.io/spring-ai/docs/1.1.x/api/index.html](https://docs.spring.io/spring-ai/docs/1.1.x/api/index.html?utm_source=chatgpt.com)

### **4. 本次学习需要记住的版本边界**

- pgvector 的 HNSW 与 IVFFlat 是 pgvector 提供的索引能力，不能与 Milvus 的同名索引简单视为相同性能。
- pgvector 0.8.0 起支持 Iterative Index Scans；过滤检索需要特别测试结果数量和召回率。
- 当前 pgvector 的 `vector` 类型与 HNSW 索引存在维度限制，不能仅凭模型输出维度就假设一定能够创建索引；需要核对实际安装版本及索引类型。
- Milvus 2.6 的 Streaming Node 等架构职责与早期版本存在差异，阅读组件资料时要保持版本一致。
- Spring AI 的 `VectorStore` 是应用层抽象，不是完整的数据库能力兼容层。特别是索引、过滤、原生客户端和运维配置，仍然需要按具体实现处理。

## **十、本次选型的核心结论**

**当前选择 PostgreSQL + pgvector，是基于现阶段业务与架构的合理取舍，不是对 Milvus 的性能否定。**

真正需要掌握的是三个判断：

1. **业务是否需要专用向量数据库？** 先看数据规模、检索负载、过滤复杂度和延迟目标。
2. **是否值得引入第二套数据库？** 要计算数据同步、一致性、部署、备份、监控和团队维护成本。
3. **未来是否需要迁移？** 通过统一数据集和 Benchmark，用 Recall\@K、P95、P99、QPS 和资源消耗验证，而不是提前凭感觉决定。

对于当前制造设备知识库，优先把 PostgreSQL + pgvector 的数据治理、混合检索、版本过滤和检索评估做扎实，比为了技术栈完整而提前部署 Milvus 更有工程价值。