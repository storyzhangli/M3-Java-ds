# D35 迭代状态

## 需求编号
D35-R01（Phase2 精读与设计清单）

## 现状核对（已实读，非臆造）
- HEAD `e666212`「D35 项目基础骨架」，工作区干净；226 main Java、0 test。
- `PG_VECTOR_PASSWORD`、`AI_API_KEY` 均已设置（Level 5 有条件执行）；PG 5432、MySQL 3306 可达。
- `RetrievedChunk` 已含 `pageNumbers`（列表）、`lifecycleStatus`；`toKnowledgeChunk()` 现仅取 `pageNumbers.get(0)`、excerpt 整段 content、section/sourceType/url 置 null。
- `ContextAssemblyService` 已有预算内 excerpt 截取 + `chunkWithExcerpt`（同样只取首页）。
- 两个 metadata 写入点均已写 pageNumbers，但均漏 paragraphIndex：
  - `DocumentVectorIndexService.toVectorDocument`（506–534 行）
  - `KnowledgeVectorStoreService.toVectorDocument`（95–107 行）
- `ChunkSource(Integer pageNumber, Integer paragraphIndex)` 在 `DocumentChunker.sourcesFor` 已真实产生段落索引，`DocumentChunk.sources()` 透传到两个写入点可聚合。
- `VectorChunkRepository` 为 JdbcTemplate，无按 chunkId 批量查询方法（需新增，避免 N+1）。
- app.js 实际 `innerHTML` 仅 1 处（119 行 `messageList.innerHTML = ''` 清空容器），第 7 行注释另含 1 个字样；`renderCitations` 已是 textContent 安全渲染，但只支持单 page、无 paragraphIndex/sourceId/URL 协议校验。
- pom 已含 `spring-boot-starter-validation`，入参校验无需新增依赖。

## 设计决定
1. **sourceId 与 citationId 区分**：`CitationDto` 新增 `sourceId`（= 真实 documentId，当前无独立来源 ID，如实映射，不擅自改 id 语义）；`id` 继续承载请求级 S 编号（citationId 语义），不改 id。
2. **字段扩展（record 加字段 + 兼容构造器，向后兼容）**：
   - `CitationDto` + `sourceId`、`pageNumbers(List<Integer>)`、`paragraphIndex(Integer)`、`score(Double)`；`page` 保留（= pageNumbers 首个，兼容旧渲染）；@JsonInclude(NON_NULL) 已隐藏缺失。
   - `KnowledgeChunk` + `pageNumbers`、`paragraphIndex`、`score`（sourceId 由 toDto 映射 documentId）。
   - `RetrievedChunk`、`RetrievalHitView` + `paragraphIndex`（各加兼容构造器）。
3. **excerpt 有限截取**：新增 `rag/support/CitationText` 静态方法，UTF-16 计数、代理对不切断、默认上限 200（可配 `app.rag.citation.excerpt-max-chars`）；`RetrievedChunk.toKnowledgeChunk()` 与 `ContextAssemblyService` 共用。
   - 关键区分：**给模型的 evidence 文本**仍按预算截取（可 >200）；**返回用户的 CitationDto.excerpt** 恒 ≤200 有限截取，来自完整 content 非改写。
4. **paragraphIndex 端到端透传**：新增 `knowledge/chunking/ChunkSources` 工具 `firstParagraphIndex(sources)`（首个非空）；两个 metadata 写入点统一经新建 `knowledge/vectorstore/VectorMetadataBuilder.buildTrace()` 写 paragraphIndex（共用一个映射方法，杜绝两处口径漂移）。
5. **存在性核验（删除竞态 + 批量）**：新增 `rag/service/CitationExistenceValidator`，注入 VectorChunkRepository；`VectorChunkRepository` 新增 `findByChunkIds(Collection)` 参数化 SQL（一次批量，避免 N+1）。RagOrchestrator 在生成后校验之后、返回 SUCCESS 之前核验，缺失→`CITATION_TARGET_DELETED` 受控失败，不展示悬空来源。
6. **URL 协议黑名单**：新增 `rag/support/UrlSafetyPolicy`（仅 http/https；拒 javascript:/data:/file:/ftp:/localhost/内网/盘符绝对路径）；`PostGenerationValidator` 增第 3 步：答案中出现 URL/本机路径→`FABRICATED_URL` 拦截；CitationDto.url 经校验不合规置 null（当前恒 null）。
7. **原因码**：`RagReasonCode` 新增 `CITATION_TARGET_DELETED`、`FABRICATED_URL`。
8. **入参校验**：`RagRequest` 加 `@NotBlank query`、`@Min(1) @Max(1000) topK`；`RagController` @RequestBody 加 `@Valid`。
9. **SSE 同语义**：SSE `citations` 事件与同步 API 均消费同一 `CitationDto`（改造后字段自动一致）；不另建一套来源。
10. **前端安全渲染**：移除 app.js 唯一 `innerHTML`（改 `replaceChildren()`）+ 注释字样；`renderCitations` 支持 pageNumbers 列表/paragraphIndex/sourceId；URL 仅服务端返回且过前端 http/https 校验才渲染 `<a target="_blank" rel="noopener noreferrer">`；页码缺失不渲染默认页码。
11. **存量回填**：paragraphIndex 缺失保持 null 兼容，不作为失败；回填方案为"重新切分入库自然写入"（不破坏真实数据），Reconciliation 仅记录覆盖范围，本次不执行真实回填（禁止删除/重建真实文档与历史向量）。

## 影响面与高风险点
- `KnowledgeChunk`/`CitationDto` record 加字段破坏全部既有构造点 → 统一补兼容构造器，逐点编译核对。
- `ContextAssemblyService` 需拆分"证据文本（预算截取）"与"引用片段（≤200）"两个文本，避免 CitationDto.excerpt 混入超长预算文本。
- 两个 metadata 写入点字段集不同，共用的 buildTrace 只覆盖公共溯源键，扩展键各点自行追加，避免误删 `chunkContentHash/splitVersion/lifecycleStatus`。
- 存在性核验在生成后、返回前执行，覆盖"检索后、响应前被删"竞态；单测需 mock 核验器，真实 DB 仅在 Level 5 用。
