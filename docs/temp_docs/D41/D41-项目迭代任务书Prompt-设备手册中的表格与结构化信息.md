# D41 项目迭代任务书 Prompt（R01）：设备手册中的表格与结构化信息（two-lp 仓）

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 结构化记录生文档。沿用 D34–D40 体裁。
> 核心原则：**表格抽取是独立处理器分支，与正文文本处理器解耦；主路径只用既有 PDFBox 坐标能力 + 文本管道表，不引入表格识别新框架、不依赖 OCR；结构化记录与正文 Chunk 双轨互相关联；能力不足处诚实标注退化，绝不静默错配、绝不伪造抽取成功。记录纳入 D39 权限元数据与 D40（R03 后）同步状态口径。**
> 编制日期：2026-11-24。编制依据：对 `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D41` 骨架（HEAD `6385d89`=D40 R03，D41 骨架**未提交**；D40 已执行 R01 `1164708`/R02 `09aaad2`/R03 `6385d89`）与 D37/D39/D40 冻结资产的真实扫描（§1），非记忆假设，**与 one 仓事实完全独立**。
> 硬约束：以本地实际代码为唯一依据；白名单内最小必要修改；不伪造指标/抽取结果/对账；空检索≠检索失败；核心契约禁用无约束 Map；保留熔断与 Token 阈值模板；**禁止引入表格识别新框架与 OCR 引擎**。

---

## §0 迭代总览与硬契约

### 0.1 目标

在 D41 模块（`D41\D41_TASIIEM`，包 `com.zl.d41`，端口 **9090**）为设备手册**故障代码表 / 备件参数表**建立稳定解析路径：表格抽取作为独立处理器分支（新包 `com.zl.d41.knowledge.table`），输出**类型化结构化记录**（表头列名 + 每行键值对 + 单位 + 页码 + 来源行号 + 表头快照），绑定 documentId/版本/页码，纳入 D39 权限元数据与 D40 同步状态口径；结构化记录供精确检索、正文表格文本行供语义召回（双轨互相关联）；源表与抽取结果逐格对账；覆盖**单位保留 / 列错位 / 重复标题 / 缺失单元格 / 图片表格**五类异常；OCR 仅可选分支（主路径不依赖），能力边界诚实标注退化。

### 0.2 硬契约（违反任意一条即任务失败）

| 编号 | 契约 | 说明 |
|---|---|---|
| H1 | 白名单最小修改 + 零差异 | 既有文件改动仅限 §2 白名单，全部追加式。**表格分支不修改既有页文本内容、不改切分器（480/60）、不改既有解析路径**；遗留语料（无表格）抽取记录数=0、Chunk 逐字节不变。回归用 **R03 冻结口径**：D37 IN_MEMORY_LOCAL 冻结基线（SHA `CD875F83611F…`）质量列 **diff=0** + PG/MySQL 计数指纹 PRE/POST 一致（R03 fingerprintMatch 口径），table.enabled 开/关双态各跑一次。白名单外既有文件零改动。 |
| H2 | 新开关默认值 + 既有默认不动 | `app.knowledge.table.enabled` 默认 **true**（功能本体，对无表格遗留语料零影响）；`app.knowledge.table.ocr.enabled` 默认 **false**（OCR 可选分支）；既有 access/sync-retry/fault-injection/hybrid/reconcile-enabled/enforce-admin 默认值**一律不动**（false）。**two_ 轨主配置默认 enabled=true 是既有事实，不改默认值**；执行期零远程经显式环境变量覆盖（§5）。 |
| H3 | 禁新框架/禁 OCR 引擎 | 表格识别**只用既有 PDFBox 3.0.7（父 POM 显式 pin）坐标能力 + JDK 文本处理 + 既有 Jsoup 传递依赖（HTML 可选分支，KP1 裁决）**；**禁止引入 tabula/camelot/deepdoctection 等表格识别新框架**，**禁止引入 tess4j 等 OCR 引擎**，**R01 不做 POI 结构化接线**（docx 表格结构诚实退化，D3）。引入任何新框架才能推进 → 熔断请示。 |
| H4 | 源表对账真实执行 | 抽取行数/列数与源表（夹具坐标真值 JSON）**逐格对账** + 抽样脚本复核，全部真实执行留痕；不伪造对账通过；检出异常（列错位/合并单元格）显式标注，**不静默错配**；置信度/退化标注诚实。 |
| H5 | 夹具受控构造 + 来源标注 | 样本=受控构造夹具（PDFBox 绘制故障码表/参数表 + 坐标真值 JSON + Java2D 渲染图片表；**D39PdfFixtureGen.java 先例**），**来源标注「受控构造」**；若执行时用户提供真实公开手册则 KP3 替换并保留来源。禁破坏性操作（DROP/prune/防火墙/停 PG）；**页级操作夹具必须用 PDF**（R03 踩坑：txt 无页面级文本 4091）。 |
| H6 | 冻结资产只读 + 夹具可清理 | D37 数据集（SHA `896BCEAA1EDE…`）/冻结基线（IN_MEMORY_LOCAL\20261120-195125，SHA `CD875F83611F…`）/评测器、D39/D40 全部证据只读；夹具 documentId 一律 **`d41-` 前缀**；**PG vector_store/two_vector_store 为跨 Dxx 共享表**（initialize-schema=false 外部 DDL），遗留 **vector_store=30 行、two_vector_store=8 行零触碰**（R03 冻结计数）；**PG 清理按 UUID 清单精删，批次前缀无效**（R03 踩坑）；MySQL 共享表（knowledge_document/knowledge_document_page/knowledge_document_content/vector_index_task/vector_chunk_index/d41_document_sync/d41_admin_audit）清理前缀限定；测试后多处核验 FIXTURE_REMAINING=0。 |
| H7 | 权限 + 审计同口径 | 表格读面经 **AccessGuard.requireDocumentVisible 同口径文档级校验**（拒绝码 **AUTH_FORBIDDEN**，sentinel **"ALL"**）；重抽取写面 **requireAdmin + d41_admin_audit 审计**（D40 口径，ObjectProvider guard 模式，disabled 时 Bean 缺席=零差异）；向量轨表格文本 Chunk 走既有元数据写入（**PG metadata 驼峰键 tenantId/deptId/deviceId**，R03 踩坑）→ D39 过滤自动生效；**不得开出不鉴权的表格读/写面**。 |
| H8 | 诚实记录 + 能力退化标注 | 编译/测试/异常矩阵/对账真实执行；四态 PASS/FAIL/BLOCKED/NOT RUN 逐用例落表；**docx 表格结构（Tika 仅文本）/真实 OCR/无线表低置信度**等能力不足处显式退化标注（DOCX_TABLE_NOT_STRUCTURED/IMAGE_TABLE_NOT_PARSED/LOW_CONFIDENCE），不伪造；空结果≠失败；分母 0 标 N/A。 |
| H9 | 凭据与预算卫生 | 密钥仅环境变量（PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY）；夹具 token 明文仅 temp_docs（不提交），DB 存 SHA-256（d41_auth_principal）；日志/证据/Git 对 token 明文与 sk-/Bearer 实值扫描零命中；surefire-reports/target/评测器 out 目录禁提交（eea63ed gitignore 先例）；temp_docs 不自动删除；不 push。 |

### 0.3 范围边界（本轮 R01 明确不做）

- 表格识别新框架（tabula/camelot/deepdoctection 等）/OCR 引擎（tess4j 等）/POI 结构化接线：不引入（H3 红线）。
- docx 表格**结构化**还原：Tika 文本路径已接线但丢表格结构，R01 诚实退化标注（D3），POI/Tika 结构化评估留 D42 候选。
- 真实 OCR 识别：无引擎，仅留接口 + 降级标注，真实 OCR NOT RUN（D4）。
- 复杂嵌套表/跨页合并单元格完美还原：启发式检出 + 标注，不保证 100% 还原（能力边界诚实）。
- D40 R03 OPEN 项（enforce-admin 启动级断言完整上下文、E3 新版本真实向量构造、真实模型轨端到端）：本轮不做，列 D42 候选。
- two_ 轨真实百炼端到端、SSE 全路径：NOT RUN（零远程预算）。
- 跨机 L5：NOT RUN（单机）。

### 0.4 与用户原始需求的十处已裁决偏差/细化（执行前必读，均有实扫依据）

| # | 用户原文 | 实测事实（two-lp 独立） | 裁决 |
|---|---|---|---|
| D1 | 「表格抽取作为独立处理器分支，与正文文本处理器解耦」 | 既有解析链：KnowledgeDocumentService.importFile→KnowledgeDocumentParser.inspect/parse（Tika MIME 探测 + 扩展名路由）；**全仓 grep TABLE/TableRow/TableCell/表格 零业务命中**（仅 import/注解/javadoc）；chunking 包无 TABLE 概念 | 新建独立包 `com.zl.d41.knowledge.table`（PdfTableExtractor 坐标分支 + TextTableExtractor 管道表分支 + TableRecordService）；importFile 解析成功后**追加式钩子**调表格分支；**不修改既有页文本/状态机/错误码**（守 H1 零差异） |
| D2 | 「不引入表格识别新框架前先评估现有 PDFBox 能力边界」 | **PDFBox 3.0.7 父 POM 显式 pin**（pdfbox.version，D27 注释与 reader 传递一致）+ 模块直接依赖；KnowledgeDocumentParser.parsePdf 逐页 PDFTextStripper **未设 setSortByPosition（默认 false）**，纯文本无表格结构 API | 主路径=**自定义 PDFTextStripper 子类收集 TextPosition 坐标（x/y/width/fontSize）→ 行/列聚类启发式**；**既有页文本为默认提取顺序（非坐标排序），双轨文本规范化口径须 KP4 冻结**（与 one 仓 sortByPosition=true 不同）；无线表/合并单元格=检出标注不静默；KP1 坐标探针实证；**禁 tabula/camelot**（H3） |
| D3 | 「评估现有 Word 能力边界，能力不足处诚实标注退化」 | **与 one 仓关键差异：docx 在白名单内且 Tika 已接线**（allowedExtensions txt/md/markdown/html/htm/pdf/docx；TikaDocumentReader 解析 html/docx 为纯文本）；Tika 文本输出**不保留表格结构**；pom 另有 spring-ai-jsoup-document-reader（Jsoup 传递依赖在） | docx 上传/文本解析**既有语义不动**；docx 表格结构 R01 **诚实退化 DOCX_TABLE_NOT_STRUCTURED**（不接 POI）；**HTML `<table>` 可选分支用既有 Jsoup**（KP1 探针裁决可用性，非新框架）；txt/md 管道表为文本表格主路径；POI/Tika 结构化评估留 KP1 记录 + D42 候选 |
| D4 | 「图片 OCR 仅作可选分支，主路径不依赖 OCR；OCR 结果标注置信度」 | **pom 无 OCR 引擎**；扫描型 PDF 既有语义=全页 blank→DOCUMENT_PARSE_FAILED **4221**「扫描型 PDF 需要 OCR」（KnowledgeDocumentParser.parsePdf） | OCR 仅留**接口 + 默认关开关**（ocr.enabled=false）+ 置信度字段预留；图片表/扫描件主路径**不崩溃**，降级标注 `IMAGE_TABLE_NOT_PARSED`；既有 4221 失败语义不变；**不引入 OCR 引擎**（H3）；真实 OCR NOT RUN，置信度无引擎时标 N/A |
| D5 | 「结构化记录与正文 Chunk 双轨互相关联」 | **与 one 仓关键差异：vector_chunk_index 无 global_start/global_end 列**（列=chunk_id uk/(document_id,version,chunk_index) uk/content/chunk_content_hash/character_count/**page_numbers**/status）；ChunkSource 仅 (pageNumber,paragraphIndex)；DocumentChunk.version 为 **String** | 双轨=结构化记录（MySQL d41_table_extract/d41_table_row）供精确检索 + 文本轨=原始页文本表格行经既有管线切为 Chunk（**不注入不改写**）；关联=**pageNumber ∈ chunk.page_numbers + 规范化表格行文本 ⊆ chunk.content 包含匹配**（vector_chunk_index.content 快照可用），命中 chunkId 集落记录关联字段；多义命中标 **LINKAGE_AMBIGUOUS**；**零切分器改动、零页内容修改、零回归影响** |
| D6 | 「结构化记录纳入 D39 权限元数据」 | D39 已交付 access 包：AccessGuard.requireAdmin/requireDocumentVisible(tenant,dept,device)/canReadDocument；拒绝码 **AUTH_FORBIDDEN**（字符串码）；sentinel **"ALL"**；KnowledgeDocumentPO 有 tenantId/deptId/deviceId；凭据表 d41_auth_principal（Bearer→SHA-256） | 表格记录**经 documentId 继承文档三键**（不冗余存权限列）；读面经 **requireDocumentVisible 同口径**；向量轨表格文本 Chunk 走既有元数据写入（**驼峰键**）→ D39 过滤自动生效；重抽取写面 requireAdmin + 审计（D7） |
| D7 | 「纳入 D40 同步状态口径」 | D40 已执行至 **R03**：SyncOperation 枚举 **DELETE/UPDATE_CLEANUP** 两操作；6 原因码（PG_UNREACHABLE/PG_DELETE_FAILED/CONFIRM_MISMATCH/MARK_FAILED/SNAPSHOT_MISSING/RETRY_EXHAUSTED）；**R03 租约语义冻结**（next_retry_at：FAILED=退避/PENDING=执行租约/SYNCED、EXHAUSTED=NULL；lease=max(backoffMs×8,5s)；retryOne 8 返回码）；syncStateRequired 默认 true fail-fast；two_ 实例 syncService=null 走 legacy fire-and-forget；ReconciliationReportView 六类 Issue | 表格记录按 **documentId×version(String)** 绑定；删除/旧版清理在 **DocumentVectorIndexService.deleteClosedLoop 与 UPDATE_CLEANUP handler 的既有 MySQL 清理事务内追加清表记录**（白名单）；失败走**既有 d41_document_sync 6 原因码 + R03 租约语义不动**，**不新建同步状态机**；对账追加 **TABLE_ORPHAN** Issue 列表（additive 字段）；重抽取 ADMIN + d41_admin_audit |
| D8 | 「公开设备手册至少 1 张故障码表 + 1 张参数表（或受控构造夹具，标注来源）」 | 本地无公开手册 PDF（用户未提供）；既有语料 classpath knowledge/equipment-fault-corpus.json（JSON 非 PDF 表格）；**D39PdfFixtureGen.java 受控 PDF 夹具先例**（temp_docs\D39）；R03 踩坑：txt 无页面级文本→页级操作用 PDF 夹具 | 样本=**受控构造夹具**：PDFBox 绘制故障码表（≥1）+ 参数表（≥1）+ 四异常变体 + Java2D 渲染图片表；**坐标真值 JSON**（每格 text/x/y/row/col/unit）=对账唯一依据；来源标注「受控构造（PDFBox 绘制）」；用户提供真实手册则 KP3 替换保留来源与真值 |
| D9 | 「D41 基础骨架仓库」 | HEAD 6385d89（D40 R03），**D41 骨架未提交**（AM pom ×2、?? src/）；**与 one 仓关键差异：D41 src/test 仅 2 个 yml，零 Java 测试**——D40 的 7 个测试文件（com.zl.d40：DocumentSyncServiceTest/InMemoryDocumentSyncRepository/SyncAssemblyBoundaryTest/DeleteClosedLoopChainTest/IndexFailureRecoveryTest/VectorReconciliationServiceDriftTest/FaultInjectingVectorStoreTest）**未随骨架迁移**；无前端 node 测试；proj_docs 至 D39-02（**D41-01/02 编号可用**） | P0 **先提交 D41 骨架**；**C01 须先迁移 D40 7 测试文件（d40→d41 包名/表名适配）作继承基线**（R03 权威：P0 组件 24/24、删除链 9/9、P2 15/15），再增 table 新单测；evidence/D41 与 temp_docs/D41 不存在需新建；logs/D41 已存在 |
| D10 | 「字段类型化 / 单位保留 / 缺失单元格 null 占位」 | 既有 DocumentChunk/vector_chunk_index 无类型化单元格概念；无单位抽取逻辑；version 全链 **String**（register 由请求指定，非 one 仓 Integer 自增） | 单元格**类型化**（number+unit / string / enum / null）；单位正则抽取**与数值同记录不拆散**；缺失单位**显式 null**；重复表头=**表头快照匹配跳过**（跨页续表）；空单元格 **null 占位不串列**；无数据行**不产生空记录**；表头快照 + 来源行号 + 页码绑定每记录；**版本字段一律 String 对齐既有契约** |

---

## §1 基线核实结果（编制时真实扫描，执行时须复核）

### 1.1 已核实事实（证据均为实读/实测）

| 项 | 实测值 |
|---|---|
| Git 基线 | HEAD `6385d89`（D40 R03 修复重试租约并补齐跨库恢复验证）；**D41 骨架未提交**（AM D41/pom.xml、AM D41/D41_TASIIEM/pom.xml、?? src/）；前序 `09aaad2`（R02 整改）、`eea63ed`（gitignore 评测器 out/class）、`1164708`（D40 R01）、`ec86b75`（D40 骨架）、`5faaf4b`（D39 R01） |
| 模块 | `D41\D41_TASIIEM`，包 `com.zl.d41`，端口 **9090**；main Java **297 个**；顶层包 access/common/config/controller/conversation/dto/**equipment**/**experiment**/history/hybrid/**knowledge**/logging/model/observability/prompt/rag/service/**two**（知识域在 knowledge，无 document 顶层包） |
| 技术基线 | Spring Boot 3.5.16 / JDK21 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17 / Spring Cloud 2025.0.0 / SCA 2025.0.0.0 / dashscope 2.23.6 / mysql-connector 8.0.33；**PDFBox 3.0.7 父 POM 显式 pin**（pdfbox.version）+ 模块直接依赖；Tika（spring-ai-tika-document-reader）**已接线**；Jsoup（spring-ai-jsoup-document-reader）依赖在 |
| 解析链 | KnowledgeDocumentParser：inspect（扩展名白名单/非空/大小/SHA-256）→parse（**Tika.detect MIME 与扩展名互证**）→路由：txt/md/markdown UTF-8 严格解码（NUL 拒绝）；html/htm、**docx→TikaDocumentReader 纯文本**；pdf→parsePdf（PDFBox Loader.loadPDF，逐页 PDFTextStripper **默认顺序无 sortByPosition**，1-based 页码，blank 标记，全 blank→4221「扫描型 PDF 需要 OCR」）；错误码 DOCUMENT_FILE_INVALID 4101 / DOCUMENT_PARSE_FAILED 4221 / DOCUMENT_STATE_CONFLICT 4091 / DOCUMENT_DUPLICATE 4092 / DOCUMENT_NOT_FOUND 4041 |
| 上传限制 | KnowledgeImportProperties（**app.knowledge.import**）：maxFileSize **5MB**、maxParsedChars 2,000,000、maxPdfPages 2000、allowedExtensions **txt/md/markdown/html/htm/pdf/docx** |
| 导入服务 | KnowledgeDocumentService：register（version 由请求指定，**String**）→importFile（PROCESSING 冲突 4091/COMPLETED 重复 4092/claimForProcessing 乐观领取）→页持久化（parserName/parserVersion 落页）；documentForScope 供权限范围查询 |
| 切分链 | knowledge.chunking：DocumentChunker/KnowledgeDocumentChunkingService；ChunkingProperties **480/60（min80/max720）**；DocumentChunk（documentId/title/**version String**/chunkIndex/content/characterCount/**pageNumbers List<Integer>**/sources/lifecycleStatus）；ChunkSource 仅 **(pageNumber,paragraphIndex)**；**无 TABLE 概念、无全局偏移** |
| 表格代码现状 | **全仓 grep TABLE/TableRow/TableCell/表格 零业务命中**（仅 import/MyBatis 注解/javadoc）；OCR 关键词唯一命中=4221 错误文案；无表格抽取/结构化记录逻辑 |
| 存储表 | MySQL：knowledge_document（version String/status/errorCode/**tenantId/deptId/deviceId**）、**knowledge_document_page**（documentId/pageNumber/content/characterCount/blankPage/parserName/parserVersion，**无 version 列**）、knowledge_document_content、vector_index_task、**vector_chunk_index**（chunk_id uk + (document_id,version,chunk_index) uk/content/chunk_content_hash/character_count/**page_numbers**/status，**无 global_start/end**）、**d41_document_sync**、**d41_admin_audit**（sync 包 JdbcTemplate 参数化 SQL，非 MyBatis）；PG **vector_store**（PG_VECTOR_TABLE 默认 vector_store，**initialize-schema=false 外部幂等 DDL**，384 维）+ **two_vector_store**（1024 维，主配置 enabled=true） |
| 遗留数据（R03 冻结） | **vector_store=30 行、two_vector_store=8 行**（R03 fingerprintMatch PRE/POST 口径）；MySQL 各表计数不变；夹具清理按 documentId/UUID 清单精删 |
| D40 同步（已执行 R03） | SyncOperation **DELETE/UPDATE_CLEANUP**；SyncReasonCode 6 码字符串常量；**R03 租约语义**：next_retry_at FAILED=退避/PENDING=租约/SYNCED、EXHAUSTED=NULL，leaseDurationNanos=max(backoffMs×8,5s)，retryOne 返回码 NO_SUCH_SYNC_ROW/NO_OP_ALREADY_SYNCED/NO_OP_LEASED/NO_HANDLER_REGISTERED/NO_OP_CONCURRENT_CLAIM/SYNCED/FAILED/PENDING；DocumentVectorIndexService.deleteClosedLoop（markPending→清向量→清关联→双端确认 markSynced/markFailed）+ DELETE/UPDATE_CLEANUP handler 注册；syncStateRequired 默认 true（syncService 缺席 fail-fast）；two_ 实例 syncService=null legacy；D41SyncController /api/v1/knowledge/sync/{status,retry,consistency} 全 requireAdmin+审计 |
| 对账 | ReconciliationReportView：orphanVectors/missingVectors/countMismatches/stuckTasks/versionMismatches/syncStuck/actionsTaken；VectorReconciliationService + VectorIndexReconcileScheduler（reconcile-enabled 默认 false） |
| 权限（D39+R03） | AccessGuard.requireAdmin()/requireDocumentVisible(tenant,dept,device)；拒绝码 **AUTH_FORBIDDEN**；sentinel **"ALL"**；AccessPolicyService 过滤谓词 deptId/deviceId OR 子树 Group 包裹；ObjectProvider<AccessGuard> 模式（access.enabled=false 时 Bean 缺席=零差异）；**enforce-admin**（APP_ACCESS_ENFORCE_ADMIN 默认 false，true 时 AccessGuard 缺席启动失败，R03-08）；凭据 d41_auth_principal |
| 端点 | /api/v1/knowledge/documents：POST（register，ADMIN）、POST /{id}/import（ADMIN）、GET /{id}、GET /{id}/pages（visible）；/{id}/chunks + /compare（visible）；vector-index POST/GET/DELETE/recover/retry/reconcile（ADMIN action=VECTOR_INDEX/VECTOR_DELETE/VECTOR_RECOVER/VECTOR_RETRY_FAILED/VECTOR_RECONCILE，GET visible）；**无表格端点** |
| 引用核验 | CitationExistenceValidator：missingChunkIds→**CITATION_TARGET_DELETED** 受控失败（D35 链路） |
| 配置 | embedding local-hash-ngram-v1 384 维；two_ qwen3.7-text-embedding 1024 维 **enabled=true（主配置）**；hybrid 默认 false；access 默认 false；sync-retry/fault-injection/reconcile-enabled 默认 false（D41_ 前缀环境变量）；ai.mode=mock；yml 注释预告 docs/temp_docs/D41/two-pgvector-schema-idempotent.sql（**尚未创建**） |
| 测试现状 | **D41 src/test 仅 application-test.yml + application-experiment.yml，零 Java 测试**；D40 模块 7 测试文件在 com.zl.d40（未迁移）；R03 权威口径：P0 组件 **24/24**、删除链 T-R03-13~21 **9/9**、P2 T-R03-22~36 **15/15**；无前端 node 测试 |
| 冻结资产 | D37 数据集 rag-evaluation-questions.v1.0.jsonl SHA **896BCEAA1EDE…**（manifest CA69A0F8AB09…）；**冻结基线=baseline\v1.0\IN_MEMORY_LOCAL\20261120-195125\baseline.csv SHA CD875F83611F356537790CE856797DA8057A5DCF735D82B63508A80CE1369C3F**（R03 甄别：20261123-205412 为 R01 重跑目录非冻结源）；评测器 temp_docs\D37\evaluator + run-evaluation.ps1 |
| 可复用脚本 | **evidence\D40\R03\scripts\run-d37-regression.ps1**（PRE SHA 核验→全量 javac 重编→IN_MEMORY_LOCAL 重跑 Rounds=3 Warmup=3 TopK=5 Threshold=0.0→剔除 4 耗时列质量列 diff=0）、run-e1-e5-r03.ps1；temp_docs\D39\**D39PdfFixtureGen.java**（PDF 夹具先例）/run-security-matrix.ps1/D39ExprProbe/D39PgFilterProbe；temp_docs\D40\ddl\d40-sync-ddl.sql + d40-principals-seed-template.sql |
| 目录 | docs\evidence\D41 与 docs\temp_docs\D41 编制时**不存在**（本轮新建）；logs\D41 已存在；proj_docs 至 D39-02（**D41-01/02 编号可用**，D40 双文档在 temp_docs\D40） |
| 工具链 | Maven `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`（JDK21 `D:\SoftwareInstall\JDK\JDK21`，仓库 D:\SoftwareInstall\Apache\repository，勿传 -Dmaven.repo.local）；PowerShell 7；`[Console]::OutputEncoding=UTF8`；无 mysql CLI（jshell/JDBC 先例） |

### 1.2 执行时复核命令（PowerShell 7，逐条留痕）

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-two-lp\M3-Java-ds"
git -C $r rev-parse HEAD; git -C $r status --porcelain   # 须 6385d89；D41 骨架未提交→P0 先提交
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "$r\D41\pom.xml" -pl D41_TASIIEM -q compile
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "$r\D41\pom.xml" -pl D41_TASIIEM dependency:tree "-Dincludes=org.apache.pdfbox,org.apache.tika,org.jsoup" # pdfbox 须 3.0.7
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
(Get-FileHash "$r\docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl" -Algorithm SHA256).Hash  # 须 896BCEAA1EDE 开头
(Get-FileHash "$r\docs\evidence\D37\baseline\v1.0\IN_MEMORY_LOCAL\20261120-195125\baseline.csv" -Algorithm SHA256).Hash  # 须 CD875F83611F 开头
# 应用起来后基线端点（端口 9090；Result.code 判成败，HTTP 恒 200；拒绝=AUTH_FORBIDDEN）
Invoke-RestMethod "http://127.0.0.1:9090/api/v1/knowledge/documents" -TimeoutSec 5 -Method Get -ErrorAction SilentlyContinue | ConvertTo-Json -Depth 4
```

### 1.3 探测门禁（P0 裁决，证据落 `docs\evidence\D41\probe\`）

| 门禁 | 待核实项 | 方法 | 通过判据 | 失败处置 |
|---|---|---|---|---|
| KP1 | 能力边界评估 | dependency:tree 复核 PDFBox 3.0.7/Tika/Jsoup；**TextPosition 坐标探针**（小 PDF 自定义 Stripper 子类收集 x/y/width 验证可用）；**docx Tika 文本表格结构丢失实证**（夹具 docx 表格→Tika 输出对照）；Jsoup HTML `<table>` 探针（可选分支裁决）；OCR 引擎 absence 确认；端点盘点 + 新表格端点分级 | 坐标可用实证；版本冻结；docx/OCR 能力缺口诚实记录；HTML 分支裁决留痕；新端点全分级 | 坐标不可用→换 PDFBox 既有能力方案，禁引新框架（H3）；能力缺口→退化标注 |
| KP2 | 存储盘点 + 指纹基线 | JDBC 只读盘点 **vector_store（须 30）/two_vector_store（须 8）**、MySQL 共享表前缀分布（遗留 DOC-*/d40- 残留/无 d41-）、d41_document_sync/d41_admin_audit/knowledge_document_page/vector_chunk_index 现状；**R03 式计数指纹 PRE 基线冻结**（无需 seeding，遗留语料已在） | 遗留计数与 R03 一致；遗留行清单冻结（清理白名单依据）；指纹脚本可复现 | 计数漂移→停止查因（禁带漂移推进）；表缺失→外部幂等 DDL 先建（two-pgvector-schema-idempotent.sql 预告位） |
| KP3 | 夹具真值冻结 | PDFBox 绘制故障码表/参数表/四异常变体/Java2D 图片表（D39PdfFixtureGen 先例）；**坐标真值 JSON**（每格 text/x/y/row/col/unit）；txt/md 管道表夹具；可选 HTML/docx 能力演示夹具；来源标注「受控构造」；用户提供真实手册则替换保留来源 | 真值 JSON 生成=对账唯一依据；夹具可复现；页级操作全 PDF；来源标注齐全 | 真值不可信→重构造，禁以抽取结果反推真值 |
| KP4 | 抽取算法与口径冻结 | 冻结表格识别条件（列聚类阈值/行间距/表头判定）、表头下沉口径、合并单元格处置、跨页续表规则、单位正则、类型化 schema、**双轨关联口径（pageNumber∈page_numbers + 规范化行文本包含匹配，默认提取顺序下的规范化规则）**、退化标注码表（DOCX_TABLE_NOT_STRUCTURED/IMAGE_TABLE_NOT_PARSED/COLUMN_MISALIGNMENT/MERGED_CELL/LOW_CONFIDENCE/LINKAGE_AMBIGUOUS） | 算法/口径/码表冻结文件生成，实现测试一律引用 | 口径冲突→先裁决冻结，禁实现期私改 |

---

## §2 分阶段实施要求

### P0 骨架提交 + 测试继承 + 探测与参数冻结

**先提交 D41 骨架**（D40 先例 ec86b75→1164708）；**迁移 D40 7 测试文件至 com.zl.d41**（包名/表名 d41_ 适配，R03 租约语义测试一并继承）作回归基线；KP1–KP4 裁决留痕；冻结 `probe\table-params.json`：`{pdfboxVersion, extractAlgorithm, headerRule, mergedCellPolicy, continuationRule, unitRegex, cellSchema, linkageRule, textNormalization, degradationCodes, fixtureTruth, legacyCounts{vector_store:30,two_vector_store:8}, baselineSha, datasetHash, skeletonHead, probedAt}`。

### P1 表格抽取独立分支（新包 `com.zl.d41.knowledge.table`，全部新文件）

1. DDL（幂等 ×2，JdbcTemplate 风格对齐 sync 包）：`d41_table_extract`（id、document_id、version【String】、page_number、table_index、header_snapshot_json、row_count、col_count、source_line_start、source_line_end、linked_chunk_ids_json、detect_flags、confidence、status、created_at、updated_at；uk_doc_version_page_table）+ `d41_table_row`（id、table_id、row_index、cells_json【类型化 {value,unit,type,null}】、source_line_number、record_hash、created_at；uk_table_row）。
2. `PdfTableExtractor`：自定义 PDFTextStripper 子类收集 TextPosition 坐标 → 行/列聚类 → 表格区域识别 → 表头判定 → 单元格类型化 + 单位抽取 → 异常检出标注；**不修改页文本**。
3. `TextTableExtractor`：txt/md 管道表（`|`）/制表符表解析，表头/分隔行识别，跨页续表表头快照匹配。
4. `HtmlTableExtractor`（**可选分支，KP1 裁决后启用**）：既有 Jsoup 解析 HTML `<table>` 结构；不引入新依赖。
5. `TableRecordService`：记录持久化（绑定 documentId/version/pageNumber/表头快照/来源行号）+ 双轨关联（pageNumber + 规范化包含匹配→linked_chunk_ids）+ 退化标注。
6. `TableController`（新端点）：GET /api/v1/knowledge/documents/{id}/tables（读，requireDocumentVisible）、GET /api/v1/knowledge/tables/{tableId}/rows（读，同口径）、GET /api/v1/knowledge/documents/{id}/tables/query（精确检索按列值，同口径）、POST /api/v1/knowledge/documents/{id}/tables/extract（重抽取，**requireAdmin + 审计 action=TABLE_EXTRACT**）。

### P2 导入钩子 + 同步绑定（白名单追加式）

| 文件 | 改动 |
|---|---|
| KnowledgeDocumentService | importFile 解析成功后**追加式**调 TableRecordService.extractAndPersist（table.enabled 门禁）；**不改既有页文本/状态机/错误码**；抽取失败不阻断文档 COMPLETED（表格分支独立降级标注） |
| DocumentVectorIndexService | deleteClosedLoop 与 UPDATE_CLEANUP handler 的既有 MySQL 清理步骤**追加** DELETE d41_table_extract/d41_table_row（按 documentId[/version]，同事务）；markPending/markFailed/markSynced 与 R03 租约语义不动；two_ legacy 分支不动 |
| VectorReconciliationService + ReconciliationReportView | 追加 **TABLE_ORPHAN** Issue 列表（记录无活动文档/版本）；修复走管线（清孤儿记录经删除事务），既有六类 Issue 不动 |
| application.yml | 仅追加 app.knowledge.table.enabled / ocr.enabled 段，不改既有行 |

### P3 权限 + 审计（白名单追加式，ObjectProvider guard 模式）

| 文件 | 改动 |
|---|---|
| TableController（P1 新建） | ObjectProvider<AccessGuard>：读面 requireDocumentVisible（经 documentForScope 取三键，拒绝 AUTH_FORBIDDEN）；重抽取 requireAdmin + d41_admin_audit 审计；disabled guard 缺席零差异 |
| AuditService（D40 既有） | 复用，TABLE_EXTRACT action 入审计（actor/role/action/target/result/reason_code/request_id） |

### P4 夹具 + 五类异常矩阵 + 源表对账

1. 夹具（**d41- 前缀 documentId**，KP3 真值）：故障码表 PDF + 参数表 PDF + 四异常变体（单位缺失/列错位合并/跨页重复表头/缺失单元格）+ 图片表 PDF + txt/md 管道表；经 register→import 管线入库（version String 指定）。
2. 五类异常矩阵（§4.3 A1–A5）+ 源表逐格对账（C06/C07/C17）。
3. 清理：**PG 按 UUID 清单精删**（R03 踩坑：批次前缀无效）+ MySQL 共享表 d41- 前缀行 + d41_table_extract/d41_table_row + sync/audit 夹具行 + 临时 principal；FIXTURE_REMAINING=0 多处核验；**vector_store=30/two_vector_store=8 遗留零触碰**；计数指纹恢复 PRE 基线。

### P5 回归门禁（夹具前后各一次）

1. access.enabled=false + table.enabled **开/关双态**：**run-d37-regression.ps1 口径**（冻结基线 SHA 核验→全量 javac 重编评测器→IN_MEMORY_LOCAL 重跑 Rounds=3 Warmup=3 TopK=5 Threshold=0.0→剔除 4 耗时列**质量列 diff=0**）；遗留语料表格记录数=0、Chunk 逐字节不变；既有端点契约不变。
2. 夹具清理后：PG/MySQL 计数指纹恢复 → 再跑回归 diff=0 → 资产无损。
3. enabled 门禁探针：非 ADMIN 重抽取 AUTH_FORBIDDEN、文档级越权 AUTH_FORBIDDEN、ADMIN 通过 + 审计落库。
4. 不一致 → BASELINE_DRIFT 熔断，停止排查。

### P6 报告与文档双交付

1. `docs\proj_docs\D41-01_设备手册表格与结构化信息全量信息与操作.md`：全量事实、命令、端点分级、抽取算法/口径、类型化 schema、五类异常报告、对账记录、用例落表、踩坑。
2. `docs\proj_docs\D41-02_设备手册表格与结构化信息技术文档与实践指南.md`：是什么→为什么→底层原理（PDFBox TextPosition 坐标聚类、默认提取顺序下的双轨包含匹配、类型化单元格、表头快照跨页续表、能力边界与退化、D39/D40-R03 绑定）→**表格抽取流程图（Mermaid graph TD，正序 + 异常分支 + 退化路径，Typora 0.11.18 兼容 subgraph ID [标题] + 节点英文双引号）**→解析规则说明（识别条件/表头下沉/合并单元格/跨页续表）→错误清单→核心代码→常见问题→工程实践。
3. 状态文件 `docs\evidence\D41\iteration_records\D41_state_R01.md`（§7 模板）。

---

## §3 工程深度专项

### 3.1 表格抽取算法（KP4 冻结，只用 PDFBox 坐标 + JDK + 既有 Jsoup）

- **坐标收集**：自定义 PDFTextStripper 子类 override writeString，收集每 TextPosition 的 x/y/width/height/fontSize/unicode；按物理页隔离。
- **行聚类**：y 坐标差 < 行高阈值（KP4 冻结，如 fontSize×0.5）聚为同一行；行内按 x 排序。
- **列聚类**：跨行 x 起始位置聚类（间隙 > 列间隙阈值判列边界）；列数=聚类簇数；表头行=首行加粗/字号差异/语义判定（启发式）。
- **表格区域识别**：连续多行具有稳定列结构（≥2 列 ≥2 行）判为表格；单列/散文本不误判。
- **合并单元格**：某行单元格跨多列（x 宽度覆盖多簇）→ 标 MERGED_CELL，值归属首列 + 其余列 null，**不静默错配**。
- **无线表**：无 ruling line 时纯坐标聚类，列边界置信度低 → 标 LOW_CONFIDENCE，对账时重点抽样。
- **跨页续表**：后续页表头与前一表 header_snapshot 匹配 → 判续表，**表头行不入数据行**，table_index 延续或新表关联前表（KP4 冻结口径）。
- **单位抽取**：单元格值正则分离数值与单位（如 `12.5 mm`/`380V`/`±0.01`），number+unit 同记录；无单位显式 null；纯文本列 type=string。
- **缺失单元格**：列簇内无 TextPosition → null 占位，**不串到相邻列**；全 null 行不产生空记录。

### 3.2 双轨关联（D5，零切分器改动，two-lp 专属口径）

- 文本轨：原始页文本（PDFTextStripper **默认顺序**已含表格行文本）经既有 DocumentChunker→DocumentChunk（480/60），**不注入不改写**。
- 结构化轨：d41_table_extract 记录页码 + 来源行号 + 行文本；**无全局偏移可用**（vector_chunk_index 无 global_start/end）。
- 关联规则（KP4 冻结）：`pageNumber ∈ vector_chunk_index.page_numbers AND normalize(表格行文本) ⊆ normalize(chunk.content)`；normalize=空白折叠/全半角统一（默认提取顺序与坐标排序可能不同，规范化规则必须冻结）；命中 chunkId 集落 linked_chunk_ids_json；**多义/零命中显式标注 LINKAGE_AMBIGUOUS/LINKAGE_MISSING，不静默**。
- 精确检索：按列值查 d41_table_row（cells_json 类型化匹配）→ 经 table_extract 关联文档 → requireDocumentVisible → 返回记录 + 关联 Chunk 引用。
- 语义召回：表格文本 Chunk 走既有向量检索（D39 权限过滤自动生效，驼峰键）。

### 3.3 权限与同步绑定（D6/D7）

- 权限：记录经 documentId 继承文档三键（documentForScope），读面 requireDocumentVisible 同口径（拒绝 AUTH_FORBIDDEN，不泄存在性口径与既有 GET 面一致）；重抽取 requireAdmin + 审计；sentinel "ALL" 语义不动。
- 同步：删除/旧版清理在 deleteClosedLoop/UPDATE_CLEANUP handler 既有 MySQL 事务追加清表记录；失败走 d41_document_sync 6 原因码 + **R03 租约语义（PENDING 租约/FAILED 退避/条件 UPDATE 领取）完全不动**；TABLE_ORPHAN 漂移经对账检出、修复走删除管线。
- 版本：记录绑定 version（**String**）；UPDATE_CLEANUP 清旧版本记录；页表无 version 列的既有事实不动（记录版本以 table_extract 自身列为准）。

### 3.4 能力边界与退化（H8 诚实）

| 能力 | 现状（two-lp 实测） | 退化标注 |
|---|---|---|
| PDF 有线表/坐标清晰表 | PDFBox 坐标聚类可解析 | 正常抽取 + 对账 |
| PDF 无线表/合并单元格 | 启发式检出，置信度低 | LOW_CONFIDENCE / MERGED_CELL / COLUMN_MISALIGNMENT 标注，不静默 |
| docx 表格 | **白名单内、Tika 文本已接线，但结构丢失** | **DOCX_TABLE_NOT_STRUCTURED**（文本轨正常，结构轨诚实退化；POI 接线 D42 候选） |
| HTML 表格 | Jsoup 传递依赖在，KP1 探针裁决 | 可选分支启用则结构化；未启用则 HTML_TABLE_NOT_EXTRACTED 标注 |
| 图片表/扫描件 | 无 OCR 引擎，主路径不崩溃 | **IMAGE_TABLE_NOT_PARSED**；扫描件既有 4221 语义不变 |
| 真实 OCR | 无引擎，仅接口 + 默认关 | NOT RUN，置信度字段 N/A |
| 复杂嵌套表 | 超出 R01 启发式能力 | 检出标注 + 诚实 N/A，不伪造还原 |

### 3.5 继承教训清单（D36–D40-R03 踩坑，实现必须规避）

- Result 包装 HTTP 恒 200：测试以 code 判成败；拒绝=AUTH_FORBIDDEN；空检索≠失败；分母 0 标 N/A。
- **不修改页文本/切分器**：表格分支独立，遗留语料零差异（H1）；双轨靠页码+包含匹配非注入。
- **R03 踩坑**：PG metadata **驼峰键**；txt 无页面级文本 4091→页级操作用 PDF 夹具；**PG 清理按 UUID 清单（批次前缀无效）**；attempt 解析勿误命中 SQL 回显行；Mockito when() 重桩副作用→doX 风格；Document metadata null 守卫→stub modelName；legacy 分支条件 syncService==null。
- **R03 租约语义**：markPending 条件 UPDATE+CASE 保留租约；findDueForRetry 三参（FAILED/PENDING 租约/宽限）；InMemory 替身须含 claimForRetry CAS——迁移测试时一并继承，禁回退 R01 旧语义。
- **D37 冻结目录甄别**：IN_MEMORY_LOCAL\20261120-195125（CD875F…）为冻结源；20261123-205412 为 R01 重跑目录，禁混用。
- 多构造候选显式 @Autowired/Qualifier；自定义 Stripper 子类非 Spring Bean 歧义。
- MySQL/PG 表跨 Dxx 共享：夹具 d41- 前缀 + UUID 清单限定清理，禁误触 vector_store 30/two_vector_store 8 遗留行。
- javac -encoding UTF-8；temp_docs 被 git 忽略，源码提交 git add -f 先例（token JSON 不提交，classes/target/out/surefire-reports 不提交，eea63ed gitignore 口径）。
- 评测器全量 javac 重编（不依赖增量判定，避免内部类缺失，R03 回归脚本三重纪律）。

---

## §4 验收标准（L1–L5 + C01–C20 + 熔断）

### 4.1 分级定义

| 级 | 内容 | 本轮要求 |
|---|---|---|
| L1 | 编译/D40 测试迁移继承/table 新单测 | 必须 PASS |
| L2 | 骨架提交/探测门禁/计数指纹基线/DDL 幂等/零差异 | 必须 PASS |
| L3 | 表格抽取对账/双轨关联/五类异常/权限同步绑定/回归无损 | 必须 PASS |
| L4 | 权限门禁/审计/凭据卫生/预算/清理 | 必须 PASS |
| L5 | 跨机复现 | NOT RUN（单机），如实标注 |

四态逐用例落表，计数合计=用例总数；N/A 须写原因。

### 4.2 逐条用例（证据路径相对 `docs\evidence\D41\`）

| 编号 | 用例 | PASS 判据 | 证据 |
|---|---|---|---|
| C01 | 编译+测试继承+新单测 | mvn test 退出码 0；**D40 7 测试文件迁移后全绿（R03 语义不回退）** + table 新单测全绿 | compile-test.txt |
| C02 | 骨架提交+KP1–KP4 | D41 骨架先提交；四门禁裁决留痕；坐标探针实证；table-params.json 冻结 | probe\ + git\ |
| C03 | DDL 幂等 | d41_table_extract + d41_table_row ×2 重跑零报错零数据变化 | ddl\ |
| C04 | 存储盘点+指纹基线 | vector_store=30/two_vector_store=8 与 R03 一致；MySQL 遗留清单冻结；计数指纹 PRE 基线可复现 | probe\kp2-* |
| C05 | 零差异回归 | table 开/关双态：R03 口径质量列 diff=0（冻结基线 CD875F…）；遗留记录数=0、Chunk 不变；计数指纹一致 | regression\ |
| C06 | 故障码表对账 | 抽取行数/列数/逐格=坐标真值 JSON；抽样复核留痕 | extract\fault-code-reconcile.json |
| C07 | 参数表对账 | 抽取行/列/逐格=真值；**单位与数值同记录** | extract\param-reconcile.json |
| C08 | 双轨关联 | pageNumber+包含匹配命中 chunkId 集；多义/零命中显式标注；精确检索+语义召回互通 | linkage\dual-track.json |
| C09 | 权限绑定 | 读面越权 AUTH_FORBIDDEN；requireDocumentVisible 同口径；ADMIN 重抽取通过+审计；disabled 零差异 | security\table-gating.jsonl |
| C10 | 同步绑定 | 删除闭环清表记录；UPDATE_CLEANUP 清旧版记录；失败走既有 6 原因码+R03 租约；TABLE_ORPHAN 对账检出 | sync\table-closure.json |
| C11 | A1 单位保留 | 数值+单位同记录不拆散；缺失单位显式 null | exceptions\a1-unit.json |
| C12 | A2 列错位 | 合并/无线表列偏移检出标注，不静默错配 | exceptions\a2-misalign.json |
| C13 | A3 重复标题 | 跨页续表重复表头不入数据行（表头快照匹配跳过） | exceptions\a3-header.json |
| C14 | A4 缺失单元格 | 空单元格 null 占位不串列；无数据行不产生空记录 | exceptions\a4-missing.json |
| C15 | A5 图片表格 | 主路径不崩溃；OCR 关→IMAGE_TABLE_NOT_PARSED 标注；扫描件 4221 不变 | exceptions\a5-image.json |
| C16 | 解析规则+错误清单 | 识别条件/表头下沉/合并处置/跨页续表规则文档化；四类异常样例/触发/处置齐全 | D41-02 + extract\rules.md |
| C17 | 源表 vs 抽取对账记录 | 全量逐格对账 + 抽样复核脚本真实执行留痕 | extract\reconciliation-log.json |
| C18 | Word/HTML 能力裁决 | docx Tika 文本正常+结构丢失实证→DOCX_TABLE_NOT_STRUCTURED；HTML Jsoup 分支裁决留痕；POI 评估记录 | capability\word-html-probe.json |
| C19 | 清理+回归复验 | FIXTURE_REMAINING=0（PG UUID 清单/MySQL 共享表/table 记录/sync/audit/principal 多处）；遗留 30/8 零触碰；指纹恢复；回归再 diff=0 | cleanup\ + regression\ |
| C20 | 卫生+预算+文档+Git | token/sk-/Bearer 零命中；DeepSeek 0/百炼 0/OCR 0；D41-01/02+状态文件齐全；提交核验不 push | security\ + cost\ + git\ |

### 4.3 五类异常矩阵（每类含触发方式 + 处置证据）

| 编号 | 场景 | 夹具构造 | 验收标准 |
|---|---|---|---|
| A1 | 单位保留 | 参数表含 `12.5 mm`/`380V`/无单位格 | 数值与单位同记录不拆散到不同 Chunk；缺失单位显式 null |
| A2 | 列错位 | 合并单元格 + 无线表夹具 | 列偏移被检出并标注（MERGED_CELL/COLUMN_MISALIGNMENT/LOW_CONFIDENCE），不静默错配 |
| A3 | 重复标题 | 跨页续表（第 2 页重复表头） | 重复表头不被当数据行入库（表头快照匹配跳过） |
| A4 | 缺失单元格 | 空格/全 null 行夹具 | 空格落 null 占位不串相邻列；无数据行不产生空记录 |
| A5 | 图片表格 | Java2D 渲染表格为图片嵌入 PDF | 主路径不崩溃；OCR 可选分支关/失败→降级 IMAGE_TABLE_NOT_PARSED 标注 |

### 4.4 熔断条件（命中即停，写状态文件后报告，不得绕过）

1. 同一根因连续失败 2 次 → 停止该链路，持久化现场，等待人工确认。
2. KP2 遗留计数漂移（vector_store≠30/two_vector_store≠8 且非本轮夹具）/ P5 回归 diff≠0 / 遗留 Chunk 被改 → 停止，先修资产，禁带漂移推进。
3. 夹具清理误触遗留行 / FIXTURE_REMAINING≠0 → 立即停止，UUID 清单与前缀限定核查。
4. **需要引入表格识别新框架/OCR 引擎/POI 接线才能推进 → 停止请示（H3）**；需破坏性操作 → 停止请示。
5. 源表对账不一致却静默放行 / 列错位静默错配 / 双轨零命中不标注 → 数据完整性缺陷，立即停止，修复后重跑对账。
6. enabled 时表格读/写面越权可访问 / 审计缺失 → 安全缺陷，立即停止该面，修复后全矩阵重跑。
7. 远程调用计数 >0（本轮结构性零远程：mock + TWO_EMBEDDING_ENABLED=false 显式覆盖，OCR 无引擎）→ BUDGET_EXCEEDED，命中即实现违约。

---

## §5 成本与预算（不伪称预算）

| 项 | 规则 |
|---|---|
| 全部测试 | local Embedding 零网络；生成走 mock（AI_MODE=mock）；**two_ 轨主配置默认 enabled=true 为既有事实——执行期显式 TWO_EMBEDDING_ENABLED=false（或 test profile）保证零远程，不改 yml 默认值** → **DeepSeek 0、百炼 0、Token 0**；**OCR 0**（无引擎）；以模式配置 + 环境变量覆盖留痕 + 零远程计数证明 |
| 计算资源 | 夹具故障码表+参数表+异常变体量级极小；坐标聚类 O(n) TextPosition；对账逐格毫秒级；回归 IN_MEMORY_LOCAL 重跑与 R03 同参计时 |
| 凭据管理 | PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY 仅环境变量（只验存在不回显）；夹具 token 明文仅 temp_docs\D41\（不提交），DB 存 SHA-256（d41_auth_principal） |

---

## §6 交付物清单与 Git 规则

| # | 交付物 | 路径 |
|---|---|---|
| 1 | 表格抽取代码（knowledge.table 新包 + 白名单追加 + 权限审计）+ D40 测试迁移 | com.zl.d41.knowledge.table 全套 + §2 白名单 + src/test 迁移 |
| 2 | 幂等 DDL + 夹具生成脚本 + 坐标真值 + 对账脚本 | docs\temp_docs\D41\（保留不删；含 yml 预告的 two-pgvector-schema-idempotent.sql 如需） |
| 3 | 探测与参数冻结 + 能力边界评估 + 端点分级 | docs\evidence\D41\probe\ |
| 4 | 结构化表格样本（源表原文 + 抽取 JSON/CSV 对照） | docs\evidence\D41\extract\ |
| 5 | 解析规则说明 + 错误清单（四类异常样例/触发/处置） | D41-02 + extract\rules.md |
| 6 | 源表 vs 抽取对账记录（行/列/抽样复核） | docs\evidence\D41\extract\reconciliation-log.json |
| 7 | 五类异常测试报告 + 双轨关联 + 权限同步证据 | docs\evidence\D41\{exceptions,linkage,security,sync}\ |
| 8 | 回归/清理/DDL/能力退化证据 | docs\evidence\D41\{regression,cleanup,ddl,capability}\ |
| 9 | 两份技术文档 | docs\proj_docs\D41-01_…全量信息与操作.md、D41-02_…技术文档与实践指南.md |
| 10 | 迭代状态文件 | docs\evidence\D41\iteration_records\D41_state_R01.md |
| 11 | 运行日志 | logs\D41\（surefire-reports/评测器 out 禁提交） |

Git：**P0 先提交 D41 骨架**；迭代提交仅含新源码、白名单改动、迁移测试、DDL/夹具/脚本**源码**（temp_docs 被忽略，git add -f 先例，token JSON 不提交）、evidence\D41、proj_docs 两份、状态文件；D37/D39/D40 资产与 MySQL/PG 遗留行零改动；提交前 `git diff --cached --stat` 核验；提交信息 `D41 项目迭代R01 设备手册表格与结构化信息`；不 push。临时文件一律不自动删除。

---

## §7 状态文件模板（D41_state_R01.md）

```markdown
# D41_state_R01 —— 设备手册中的表格与结构化信息（two-lp 仓）
- 迭代/切片：D41 / R01；骨架基线 commit：6385d89（D41 骨架 P0 先提交，执行时复核）；本切片交付 commit：<git>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / PDFBox 3.0.7（父 POM pin）/ MyBatis-Plus 3.5.17 / PG 18.7+pgvector 0.8.7 / MySQL 8
- 冻结参数：pdfboxVersion=3.0.7 baselineSha=CD875F83611F…（IN_MEMORY_LOCAL\20261120-195125）datasetHash=896BCEAA1EDE… denyCode=AUTH_FORBIDDEN sentinel=ALL legacyCounts={vector_store:30,two_vector_store:8} extractAlgorithm=<KP4> cellSchema=<KP4> linkageRule=pageNumber+规范化包含匹配 degradationCodes={DOCX_TABLE_NOT_STRUCTURED,IMAGE_TABLE_NOT_PARSED,COLUMN_MISALIGNMENT,MERGED_CELL,LOW_CONFIDENCE,LINKAGE_AMBIGUOUS} fixtureTruth=<KP3>
- 偏差裁决：D1 独立 knowledge.table 分支不改页文本 / D2 PDFBox 坐标聚类+默认顺序规范化 / D3 docx Tika 文本在结构退化+HTML Jsoup 可选 / D4 OCR 无引擎仅接口降级 / D5 双轨页码+包含匹配（无全局偏移）/ D6 权限继承三键 AUTH_FORBIDDEN / D7 同步复用 DELETE+UPDATE_CLEANUP+R03 租约+TABLE_ORPHAN / D8 受控构造夹具坐标真值 / D9 骨架先提交+D40 7 测试迁移 / D10 类型化+单位同记录+null 占位+version String（执行时确认）

## 门禁与用例落表
| 编号 | 状态(PASS/FAIL/BLOCKED/NOT RUN/N/A) | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | … | probe\… | … |
| C01–C20 | … | … | … |
| A1–A5 | … | exceptions\… | 触发+处置证据 |

## 分级结论
L1=… L2=… L3=… L4=… L5=NOT RUN（单机）

## 抽取与对账结论
- 故障码表/参数表逐格对账=真值；双轨关联命中/多义标注；五类异常处置；能力退化诚实标注

## 成本实耗
DeepSeek calls=0 tokens=0（mock）；百炼 calls=0（TWO_EMBEDDING_ENABLED=false 显式覆盖）；OCR calls=0（无引擎）；夹具清理 FIXTURE_REMAINING=0；计数指纹恢复基线（30/8）

## 诚实声明
- 已执行并通过：… / 已执行但失败：… / 尚未执行：… / N/A 项及原因（docx 结构/真实 OCR/复杂嵌套表）：…

## 遗留与 D42 候选
docx 表格 POI/Tika 结构化接线 / 真实 OCR 引擎引入评估 / 复杂嵌套表还原 / D40 R03 OPEN（enforce-admin 启动断言/E3 真实新版本向量/真实模型轨）/ 跨机 L5
```

---

## §8 官方文档溯源（执行时复核，不锁定记忆值）

- 《Apache PDFBox 3.0.7 Javadoc — PDFTextStripper / TextPosition | PDFBox 文本提取与坐标 API》：https://pdfbox.apache.org/docs/3.0.7/javadocs/org/apache/pdfbox/text/PDFTextStripper.html （TextPosition 坐标收集与逐页提取，表格聚类依据）
- 《Apache PDFBox — Text Extraction | PDFBox 文本提取》：https://pdfbox.apache.org/3.0/textextract.html （默认提取顺序与 sortByPosition 差异、无原生表格检测器依据）
- 《Apache Tika — Documentation | Tika 文档解析》：https://tika.apache.org/3.2.3/ （TikaDocumentReader 文本输出不保留表格结构的能力边界依据，执行时按 dependency:tree 实际版本复核）
- 《Spring AI Reference — ETL Pipeline / Tika Document Reader | Spring AI 文档读取器》：https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html （spring-ai-tika/pdf-document-reader 1.1.8 接线依据）
- 《jsoup Java HTML Parser — Cookbook | jsoup HTML 解析》：https://jsoup.org/cookbook/extracting-data/selector-syntax （HTML `<table>` 可选分支选择器依据，既有传递依赖）
- 《MySQL 8.0 Reference Manual — JSON Functions | MySQL JSON 函数》：https://dev.mysql.com/doc/refman/8.0/en/json-functions.html （类型化单元格 cells_json 与表头快照存储依据）
- 本地权威依据：D37 冻结基线/数据集/评测器（docs\evidence\D37\，IN_MEMORY_LOCAL\20261120-195125）、D39 R01 权限冻结（docs\evidence\D39\）、**D40 R03 状态与租约冻结**（docs\evidence\D40\iteration_records\D40_state_R03.md、R03\scripts\run-d37-regression.ps1）、D39 PDF 夹具先例（docs\temp_docs\D39\D39PdfFixtureGen.java）、D41 骨架实扫（§1.1）。

> 溯源纪律：不得伪造文档地址/源码内容/抽取结果/对账/测试结果；无法确认的信息标「待运行时核验」。本任务书所有实测值来自编制时真实扫描，执行时若漂移，以执行时实读为准并在状态文件记录差异。能力不足处（docx 结构/OCR/复杂嵌套表）诚实标注退化，绝不静默错配或伪造抽取成功。