# D35 知识笔记：RAG 引用溯源与 Citation Validation

>
> 技术基线：JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / PostgreSQL 18.7 / pgvector 0.8.7 / VM 192.168.200.153
>
>
> 口径标记：【官方】= Spring AI / PostgreSQL / pgvector 官方文档；【实测】= D31–D33 在 192.168.200.153 真实 psql 跑通的输出；【原理】= 机制解释；【结论】= 工程判断；【缺口】= 当前工程 schema 尚未覆盖；【待接 LLM】= 本机无 ChatModel bean，行为层只做设计不编输出。

---

## 0. 项目核验与边界声明

| 项                                   | 状态                                                         |
| ------------------------------------ | ------------------------------------------------------------ |
| 当前 chunk 表 `d31_life.rag_chunk`   | 【实测】已建，字段含 `doc_key / doc_version / split_version / chunk_no / content / embedding(384) / device / current_version` |
| 唯一约束                             | 【实测】`UNIQUE(doc_key, doc_version, split_version, chunk_no)`，D31 真跑触发过 `duplicate key value violates unique constraint` |
| HNSW 索引                            | 【实测】`idx_rag_chunk_hnsw`，D33 EXPLAIN ANALYZE 命中 Index Scan，0.034 ms |
| `page / paragraph / source_url` 字段 | 【缺口】当前 schema 未建，本次只在 DTO 层预留字段，不伪造页码 |
| 历史版本保留                         | 【实测】D31 C 步 v1=3、v2=3 并存，`current_version` 标记当前版本 |
| ChatModel bean                       | 【待接 LLM】本机只有 `LocalHashingEmbeddingModel`，无 ChatModel 端点；Citation Validation 的行为指标不编 |

---

## 1. 三类 ID 的职责与生命周期

【结论】一次 RAG 回答里同时存在三层身份，混在一起是 Citation 出问题的根因。

| ID                         | 作用域                   | 生命周期                                             | 示例                | 谁生成                     |
| -------------------------- | ------------------------ | ---------------------------------------------------- | ------------------- | -------------------------- |
| **Citation ID**（`[S1]`）  | 单次请求 / 单次回答内    | 请求结束即失效，下次请求 `[S1]` 指另一个 chunk       | `[S1]`、`[S2]`      | 服务端在 Prompt 注入时分配 |
| **Source ID**（`doc_key`） | 跨请求、跨回答稳定       | 同一业务文档永久不变，除非文档被物理删除             | `mp500-qg`          | 入库时由业务侧指定         |
| **Chunk ID**               | 跨请求稳定，但随版本漂移 | 由 `(doc_key, doc_version, chunk_no)` 三元组唯一确定 | `(mp500-qg, v2, 3)` | 入库时由切分器产出         |

【原理】为什么 `[S1]` 不能直接当 Chunk ID 回查：

- 本次请求 `[S1]` 是 MP500 chunk，下次请求 `[S1]` 可能是 MF400 chunk；
- 数组下标、Map key 都是临时身份，重启进程就错位；
- 真正持久化的只能是 `(doc_key, doc_version, chunk_no)` 三元组——这正是 D31 唯一约束的物理含义。

【结论】三个反直觉点：

1. **同一份文档多个 chunk** → 它们共享 `doc_key`，但 `chunk_no` 不同，在 Citation DTO 里是多条记录；
2. **同一 chunk 被多次引用** → 正文里出现 `[S1]` 三次，DTO 里只返回一条 `citationId=S1`，不复制三份；
3. **跨请求禁止复用 Citation ID** → 服务端必须按 `requestId` 隔离映射表，不能用全局 Map。

---

## 2. Document Version：为什么引用必须绑版本

### 2.1 版本与当前态

【实测】D31 已经落了版本语义：

```
-- D31 C 步真跑结果
SELECT doc_version, current_version, count(*)
FROM d31_life.rag_chunk GROUP BY 1,2;
-- v1 | false | 3
-- v2 | true  | 3
```

【结论】三个状态必须分开：

| 状态         | SQL 判定                                              | 引用时的处理                              |
| ------------ | ----------------------------------------------------- | ----------------------------------------- |
| 当前有效版本 | `current_version = true`                              | 正常回答 + 引用                           |
| 历史版本     | `current_version = false` 且 `doc_version < max`      | 历史回答可追溯；新回答不引用              |
| 失效 / 草稿  | `current_version = false` 且 metadata 标记 deprecated | 新回答必须排除；引用命中则报 `CITE_STALE` |

### 2.2 文档更新后历史引用不能错位

【原理】假设 MP500 手册 v2 第 3 段说"压力阈值 0.4 MPa"，v3 改成 0.6 MPa：

- 若 Citation DTO 只存 `doc_key + chunk_no`，前端点开历史回答会看到 v3 的 0.6 MPa，与当时生成答案时依据的 0.4 MPa 矛盾；
- 所以 Citation DTO 必须同时固化 `doc_version`，历史回答点开时按 `(doc_key, v2, chunk_no=3)` 回查，即使 v3 已生效也能取出 v2 原文。

【结论】这要求入库层**追加版本而不是覆盖版本**——D31 的 append-only 幂等设计（ON CONFLICT DO NOTHING + 新 version 行）天然支持这一点。

---

## 3. Page / Paragraph：原文定位

### 3.1 当前工程的真实状态

【缺口】D31 建表时只存了 `content`，没有 `page / paragraph / heading` 字段。这意味着：

- 现在能给前端返回 `doc_title + chunk_no + 内容片段`；
- 还不能跳转到 PDF 第 12 页、Word 第 5 段；
- 这一层在 D35 不伪造，DTO 字段先预留，等 D36+ 做 PDF 解析时补 metadata。

### 3.2 工程原则

【结论】

1. **页码 / 段落号必须来自解析器**：PDFBox 输出的 `PDPage.getPageNumber()`、Apache Tika 的 XHTML 标题层级，入库时写进 metadata；
2. **不能让模型猜页码**：模型说"见第 12 页"而 PDF 只有 8 页是常见幻觉，结构校验直接拒绝；
3. **Chunk 跨段跨页时**：metadata 里记录 `page_start / page_end`，前端展开时显示"第 11–12 页"；
4. **OCR / 表格**：表格 chunk 的 `paragraph` 置空，`chunk_type = TABLE`，引用时提示"见表 3"而不是编造段落号。

---

## 4. Citation Mapping 完整链路

```
graph TD
    subgraph IN["入库层（D31 已实现）"]
        A1["原始文档 PDF/MD"] --> A2["解析 + 切分"]
        A2 --> A3["rag_chunk 表<br/>doc_key+doc_version+chunk_no"]
    end

    subgraph REQ["请求层（D33 已实现）"]
        B1["用户问题"] --> B2["Query Embedding"]
        B2 --> B3["VectorStore.similaritySearch<br/>带 device/current_version 过滤"]
        B3 --> B4["服务端证据集 List&lt;Document&gt;"]
    end

    subgraph MAP["映射层（D35 新增）"]
        C1["为本次请求分配 [S1..Sn]<br/>Map&lt;String, ChunkRef&gt;"]
        C2["把证据块包进 Prompt<br/>&lt;retrieved_context&gt;[S1]...&lt;/retrieved_context&gt;"]
        C2 --> C3["ChatClient.call()"]
        C3 --> C4["模型输出 answer + 内联 [S1][S2]"]
        C4 --> C5["正则抽取所有 [Sn]"]
    end

    subgraph VAL["校验层（D35 新增）"]
        D1{"结构校验<br/>Sn 是否在 Map 中?"}
        D1 -- "否" --> E1["CITE_FABRICATED"]
        D1 -- "是" --> D2{"字段完整?<br/>source 是否 current?"}
        D2 -- "否" --> E2["CITE_STALE / CITE_BAD_LOCATION"]
        D2 -- "是" --> D3{"语义校验<br/>chunk 原文支持 Claim?"}
        D3 -- "否" --> E3["CITE_MISALIGNED"]
        D3 -- "是" --> D4["组装 Citation DTO"]
    end

    subgraph OUT["响应层"]
        F1["answer: String"]
        F2["citations: List&lt;CitationDTO&gt;"]
        F3["traceId + 校验结果日志"]
    end

    A3 -.->|"SQL 取证据"| B4
    B4 --> C1
    C1 --> C2
    D4 --> F1
    D4 --> F2
    D4 --> F3
```

【结论】关键不变量：

- **模型只能看见 `[S1]..[Sn]`，看不见 doc_key、doc_version、URL、页码**；
- **模型输出里出现的任何真实来源字段都不可信**，必须由服务端根据 `[Sn]` 反查 `Map` 后填充；
- **DTO 的 `originalContent` 来自 DB，不来自模型回显**——防止模型在"引用原文"时篡改内容。

---

## 5. Citation Validation：结构校验 vs 语义校验

### 5.1 七类异常与原因码

| 原因码               | 异常类型                                      | 判定位置                  | 处置                                                         |
| -------------------- | --------------------------------------------- | ------------------------- | ------------------------------------------------------------ |
| `CITE_FABRICATED`    | 模型引用 `[S9]`，本次只有 S1–S3               | 结构校验：Set.contains    | 删除该引用，记录日志，必要时整答拒答                         |
| `CITE_MISSING`       | 事实结论句没有任何 `[Sn]`                     | 结构校验：分句后扫引用    | 标记 `groundedness_degraded`，在答案后追加"以下结论未标注来源" |
| `CITE_STALE`         | 引用的 doc_version 已 `current_version=false` | 结构校验：join rag_chunk  | 历史回答放行；新回答拒绝，要求重新检索                       |
| `CITE_DUPLICATE`     | 同一 chunk 在 Map 里被分配两个 `[Sn]`         | 结构校验：入库前去重      | 服务端合并，DTO 只返回一条                                   |
| `CITE_MISALIGNED`    | `[S1]` 存在，但 S1 原文不支持该 Claim         | 语义校验                  | 删 Claim 或降级为"未支持结论"                                |
| `CITE_BAD_LOCATION`  | page/paragraph 字段为空或越界                 | 结构校验：metadata 完整性 | 隐藏位置字段，只显示文档标题                                 |
| `CITE_CONTENT_DRIFT` | DTO 里的 originalContent 与 DB 不一致         | 结构校验：hash 比对       | 以 DB 为准，告警                                             |

### 5.2 两层校验不能互相替代

【原理】

- **结构校验**是 O(1) 查表：`Sn ∈ map?`、字段非空、source 在有效期。可以纯代码、毫秒级、必做。
- **语义校验**要判断"S1 原文是否真支持结论"——这本质是一个 NLI（Natural Language Inference）问题，最小闭环用 Prompt 让模型判，生产可以上 NLI 模型或规则。

【结论】结构校验通过 ≠ 引用可信。反例：

```
模型输出：X100 轴承已经损坏，需要立即更换。[S1]
S1 原文：轴承磨损可能导致设备振动。
```

`[S1]` 存在、字段完整、版本有效——结构校验全过，但 S1 只支持"可能性"，不支持"已经损坏"。这是 `CITE_MISALIGNED`，必须靠语义校验拦。

### 5.3 一引多证 / 多证一引

【结论】

- 一条引用可以支持多个 Claim：`[S1]` 在答案里出现 3 次，DTO 只返回一条；
- 多个引用共同支持一条 Claim：答案写"建议停机检查 [S1][S2]"，DTO 里 S1、S2 都返回，前端两个角标都可点；
- 映射关系是 `ClaimID × CitationID` 的多对多，不是一对一。

---

## 6. Citation DTO 与 API Response

【结论】服务端根据真实 `Map<Sn, ChunkRef>` 构建 DTO，**不允许模型回显任何字段**。

```
public record CitationDTO(
    String citationId,        // "S1"，请求内引用角标
    String sourceId,          // doc_key，业务稳定标识
    String documentId,        // doc_key + doc_version 复合
    String documentVersion,   // "v2"
    String documentTitle,     // "PIXMA MP500 快速指南"
    String chunkId,           // "(mp500-qg, v2, 3)"
    Integer page,             // 缺口：当前为 null，等 PDF 解析补
    Integer paragraph,        // 缺口：当前为 null
    String originalContent,   // 来自 DB.content，不来自模型
    String sourceUrl          // 仅当真实存在；本机不提供，置 null
) {}
```

响应结构：

```
{
  "answerId": "a_20261118_001",
  "answer": "按 STOP 键后掀开前盖，静置 2 分钟再取墨盒 [S1]。",
  "citations": [
    {
      "citationId": "S1",
      "sourceId": "mp500-qg",
      "documentVersion": "v2",
      "documentTitle": "PIXMA MP500 快速指南",
      "chunkId": "(mp500-qg, v2, 3)",
      "page": null,
      "originalContent": "按 STOP 后掀开前盖，静置 2 分钟再取墨盒。"
    }
  ],
  "validation": {
    "structuralPassed": true,
    "semanticPassed": true,
    "issues": []
  }
}
```

【结论】安全红线：

1. **`sourceUrl` 字段宁可不返回，也不让模型生成**——防止模型拼出 `https://internal/path?x=` 钓鱼链接；
2. **前端点击引用回源时必须重新鉴权**：不能因为答案里出现了 `[S1]` 就放行该用户查看 S1 原文，要在 `/api/citations/{id}` 端点重跑一次 tenant / device / ACL 校验；
3. **防路径穿越**：sourceUrl 即使服务端生成，也只能是白名单内的相对路径；
4. **DTO 字段与 Prompt 输入字段严格对齐**：模型看见什么，DTO 才能有什么；模型没看见的（page、url），DTO 也不能编。

---

## 7. 工程知识补充（任务书 §三 十条精简落地）

| #    | 主题           | D35 落地决策                                                 |
| ---- | -------------- | ------------------------------------------------------------ |
| 1    | 稳定身份       | Source ID = `doc_key`；Chunk ID = 三元组；入库幂等靠 D31 唯一约束 |
| 2    | 引用作用域     | `Map<String, ChunkRef>` 必须挂在 `requestId` 上，请求结束即丢，禁止 static 缓存 |
| 3    | 引用与结论绑定 | 多对多，DTO 不按 Claim 复制，按 Chunk 去重                   |
| 4    | 历史引用有效性 | DTO 固化 `doc_version`，回查按三元组，不跟随 current_version 漂移 |
| 5    | 原文完整性     | `content_hash`（sha256）入库时计算；回查时比对，不一致报 `CITE_CONTENT_DRIFT` |
| 6    | 来源权限       | 点引用时重跑权限过滤；与检索阶段的 `device='MP500'` 是同一套规则 |
| 7    | 引用去重       | 同 doc 多 chunk 时，前端按 `sourceId` 聚合展示，chunk 折叠   |
| 8    | 数据一致性     | ChunkRef 只在服务端一份，Map / DTO / 审计日志都从它派生      |
| 9    | 引用安全       | 白名单 URL、重鉴权、禁止模型输出 sourceUrl                   |
| 10   | 审计           | 记录 `answerId / requestId / citations / validationResult / modelVersion / promptVersion`，敏感全文不进普通日志 |

---

## 8. Spring AI 1.1.8 工程关联

【官方】D35 涉及的类与职责：

| 角色         | Spring AI 抽象                                               | 在 D35 里干什么                                              |
| ------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| 证据载体     | `org.springframework.ai.document.Document`                   | `getId()` 可作为 chunkId；`getMetadata()` 取 doc_key / doc_version / chunk_no / device |
| 检索入口     | `VectorStore.similaritySearch(SearchRequest)`                | 返回的 `List<Document>` 是本次唯一可信证据源                 |
| 检索参数     | `SearchRequest.builder().topK().similarityThreshold().filterExpression()` | `filterExpression("device == 'MP500' && current_version == true")` 保证进入证据集的就是合法来源 |
| 模型调用     | `ChatClient.prompt().system(s).user(u).call().content()`     | 模型只回字符串，里面带 `[Sn]`；不直接返回结构化 Citation     |
| 模板         | `PromptTemplate`                                             | 把证据块拼成 `<retrieved_context>[S1]...`                    |
| **不做的事** | Spring AI **不提供** Citation Mapping / Validation 框架      | 这一层必须业务 Service 自己写——不要试图找一个 `CitationValidator` bean |

【结论】Spring AI 给你的是"检索 + 调模型"，引用链路是应用层责任。`Document` 自带的 metadata 就是 Citation DTO 的数据源，不要让模型回显。

---

## 9. 实测证据与待接 LLM

### 9.1 机制层真跑结果（D31–D33 已验证）

| 机制           | 真实结果                                                     | 对 D35 的意义                                      |
| -------------- | ------------------------------------------------------------ | -------------------------------------------------- |
| Chunk 物理主键 | `UNIQUE(doc_key, doc_version, split_version, chunk_no)`，D31 E 步重复业务键报 `duplicate key` | Chunk ID 三元组稳定，幂等入库后不会错位            |
| 版本并存       | v1=3 行 `current_version=false`，v2=3 行 `true`              | 历史引用可回查 v1，新回答只看 v2                   |
| 权限过滤       | D33 R2 加 `device='MP500'` 后只剩 3 条 MP500（距离 1.053/1.095/1.118） | 证据集本身已经过权限过滤，Map 里不会混入越权 chunk |
| 索引性能       | `Index Scan using idx_rag_chunk_hnsw`，Execution 0.034 ms    | 校验层加在请求链路里不构成性能负担                 |
| 维度拦截       | 插 `[1,2,3]` 报 `expected 384 dimensions, not 3`             | 入库层硬约束，防止 chunk 数据漂移                  |

### 9.2 行为层待接 LLM（不编）

【待接 LLM】本机无 ChatModel bean，以下指标需要 OpenAI 兼容端点后真跑：

| 测试项                                     | 预期                                | 实际                         |
| ------------------------------------------ | ----------------------------------- | ---------------------------- |
| 模型是否输出 `[S9]` 这种伪造编号           | 偶发，结构校验必须拦                | 待接 LLM                     |
| 模型是否在"引用原文"里篡改 content         | 偶发，hash 比对必须拦               | 待接 LLM                     |
| 语义校验对 `CITE_MISALIGNED` 的召回率      | 待评测集                            | 待接 LLM                     |
| 模型是否会自行编出 sourceUrl / 页码        | 应被 Prompt 禁止 + DTO 不回传双保险 | 待接 LLM                     |
| 文档 v3 上线后，历史回答按 v2 回查是否正确 | 三元组回查，应正确                  | 机制层已保证，行为层待接 LLM |

---

## 10. 验收 6 问（对应任务书 §五）

1. **如何保证 `[S1]` 对应真实 chunk？** 服务端在调用模型前构建 `Map<String, ChunkRef>`，Prompt 里只暴露编号；模型回包后正则抽 `[Sn]`，按 Map 反查，不在 Map 里的直接报 `CITE_FABRICATED`。
2. **为什么不能让模型生成 URL / 版本 / 页码？** 模型对这些字段没有可信访问路径，它编出来的 12 页、0.4 MPa、`https://...` 都是幻觉。DTO 字段全部由服务端从 DB metadata 填充。
3. **文档更新后历史回答怎么追溯？** DTO 固化 `(doc_key, doc_version, chunk_no)` 三元组，回查按三元组，不跟随 `current_version` 漂移。v3 上线后历史回答点开仍看 v2 原文。
4. **怎么识别"引用存在但不支持结论"？** 结构校验过了之后做语义校验（NLI 或 Prompt），判断 S1 原文是否蕴含该 Claim；不蕴含就报 `CITE_MISALIGNED`，删 Claim 或降级。
5. **怎么防伪造引用、越权、内容被替换？** 伪造靠 Map 查表；越权靠点击引用时重跑权限过滤；内容被替换靠 `content_hash` 入库时固化、回查时比对。
6. **API 怎么返回完整可追溯引用？** `answer + citations[] + validation{}` 三段分离；citations 里每条都能从 `chunkId` 三元组回到 DB 原始行，前端可展开原文。

---

## 附录：官方参考资料

- Spring AI Document | Spring AI 文档与元数据模型：[](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html)[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html)
- Spring AI VectorStore | Spring AI 向量存储抽象：[](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html)[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html)
- Spring AI SearchRequest | Spring AI 相似度检索请求：[](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/SearchRequest.html)[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/SearchRequest.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/SearchRequest.html)
- Spring AI ChatClient | Spring AI 同步式对话客户端：[](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html)[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html)
- Spring AI Retrieval Augmented Generation | Spring AI 检索增强生成参考：[](https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html)[https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html](https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html)
- PostgreSQL 18 SQL INSERT ON CONFLICT | PostgreSQL 18 插入冲突处理：[](https://www.postgresql.org/docs/18/sql-insert.html)[https://www.postgresql.org/docs/18/sql-insert.html](https://www.postgresql.org/docs/18/sql-insert.html)
- pgvector README | pgvector 向量类型与 HNSW 索引：[](https://github.com/pgvector/pgvector)[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)
- OWASP LLM01:2025 Prompt Injection | OWASP 2025 大模型风险：提示词注入：[](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)[https://genai.owasp.org/llmrisk/llm01-prompt-injection/](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)

---

## 核心结论

**模型只负责生成答案和 `[Sn]` 编号；服务端负责 Map、Validation、DTO 三件事。** 任何一条引用最终都能从 API Response 的 `chunkId = (doc_key, doc_version, chunk_no)` 三元组回到 `d31_life.rag_chunk` 的原始行——这就是"可追溯"的物理含义。结构校验挡得住伪造编号，语义校验挡得住引用错位，版本固化挡得住文档更新漂移，重鉴权挡得住越权回源。