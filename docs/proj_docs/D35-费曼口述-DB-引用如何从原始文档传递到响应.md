# 费曼口述：引用如何从原始文档传递到响应

**技术基线：** JDK 21、Spring Boot 3.5.x、Spring AI 1.1.8、PostgreSQL 18.6、pgvector 0.8.x。

**应用场景：** 制造设备故障知识库与工单助手。

共一百四十六道题，分十一个阶段。

**核心结论：引用不是模型自己编出来的文件名，而是一条从原始文档到最终响应的可追溯链路。原始文档入库时建立元数据 → 切块时 Chunk 继承来源 → 向量库存储时 Metadata 随文档一起写入 → 检索时带着来源返回 → Context Assembly 给每个证据分配编号 → Prompt 让模型引用编号 → 后处理校验编号是否合法 → CitationResolver 把编号翻译成真实来源 → 前端展示可点击的引用。任何一环断了，引用就是假的。**

## RAG 引用传递全景

```
原始 PDF/Word/Excel
    │
    ▼ 文档解析 + 元数据提取
source_document 表（documentId, fileName, version, hash）
    │
    ▼ 切块 + Metadata 继承
document_chunk 表（chunkId, documentId, page, section, text, embedding）
    │
    ▼ 向量检索（带 Metadata 返回）
List<Document>（chunkId, documentId, page, text, score）
    │
    ▼ Context Assembly（证据编号映射）
Evidence Map: [S1]→chunk-015, [S2]→chunk-022
    │
    ▼ Prompt 注入（要求模型标注引用编号）
模型输出："E023 是传感器异常 [S1]，需断电检查 [S2]"
    │
    ▼ 后处理校验（编号存在性 + 权限 + 语义支持）
    │
    ▼ CitationResolver（编号 → 真实来源）
Citation DTO: 手册名, 版本, 页码, 章节, 链接
    │
    ▼ 前端展示（可点击跳转到原始 PDF 页码）
```

---

## 一、理解 RAG 引用的基本概念

**说话人1: 问题一，什么是 RAG 引用？它在 RAG 系统中解决什么问题？**

**说话人1: 答，** Citation 是答案中每个事实性陈述对应的来源证据。它解决两个问题：第一，用户能验证答案是不是真的——点一下引用就能看到原始手册；第二，系统能定位答案来自哪段知识，方便排查错误和知识库维护。没有引用的 RAG 就是黑盒，答案对不对全靠猜。

**说话人1: 问题二，为什么 RAG 系统不能只返回模型生成的答案，还应该返回答案依据？**

**说话人1: 答，** 因为大模型会幻觉。维修场景里，模型说"换 F3 保险"，你不告诉他这句话来自哪本手册第几页，他不敢照着做——万一模型编的呢？引用是可信度的来源：有证据支撑的答案，用户才敢执行。

**说话人1: 问题三，什么是 Source、Document、Chunk 和 Citation？**

**说话人1: 答，**

| 概念             | 含义                                        |
| ---------------- | ------------------------------------------- |
| Source（源）     | 最原始的文件，如 `INV-3000维修手册v3.2.pdf` |
| Document（文档） | 系统中经过解析、入库的文档记录              |
| Chunk（文本块）  | 文档切块后的最小检索单元，一段几百字        |
| Citation（引用） | 答案中某句话对应的具体 Chunk 来源           |

层级关系：Source → Document → Chunk → Citation。

**说话人1: 问题四，Citation 与普通网页超链接有什么区别？**

**说话人1: 答，** 网页超链接指向一个页面；RAG Citation 指向具体的证据片段，精确到页码、段落。而且 Citation 不仅是链接，还意味着"答案中的这句话是从这段内容来的"——有语义对应关系，不是随便放个链接。

**说话人1: 问题五，什么是原始文档？它与经过解析、清洗和切块后的文档有什么区别？**

**说话人1: 答，** 原始文档是用户上传的 PDF/Word 原件，是事实的最终来源。解析清洗后的文本是处理过的中间产物，可能丢失格式、改变语序。引用最终要追溯到原始文档——用户要看到的是手册原文，不是系统处理过的文本。

**说话人1: 问题六，为什么引用必须能够追溯到真实存在的原始文档？**

**说话人1: 答，** 因为如果引用指向的文档不存在了，用户点进去看不到内容，这个引用就是假的。维修场景里，工程师要对照手册确认维修步骤——引用断了，答案可信度就没了。

**说话人1: 问题七，什么是 Evidence？证据与引用之间是什么关系？**

**说话人1: 答，** Evidence 是检索回来、进入上下文的那段具体文本。Citation 是答案陈述和 Evidence 之间的对应关系。证据是"原料"，引用是"标注哪个结论用了哪个原料"。

**说话人1: 问题八，为什么检索到某个 Chunk，不代表它一定能够作为最终答案的有效引用？**

**说话人1: 答，** 因为 Chunk 相关 ≠ Chunk 支持结论。检索回来的 Chunk 可能是提到了 E023，但讲的是故障现象，不是维修步骤。模型拿它当维修步骤的引用，就是错误引用。检索回来只是候选，最终能不能当引用要过语义校验。

**说话人1: 问题九，什么是引用可追溯性？**

**说话人1: 答，** Traceability：从答案中的引用标记出发，能一步步追到：哪个 Chunk → 哪份文档 → 哪个版本 → 原始文件哪一页。任何一环断了，追溯链就断了。

**说话人1: 问题十，什么是引用依据约束？它与普通的答案生成有什么区别？**

**说话人1: 答，** Grounding：模型只能基于提供的证据回答，不能用自己的内部知识。普通生成是模型自由发挥；Grounding 生成是"只能用我给你的材料回答，材料里没有的就说不知道"。引用是 Grounding 的可验证化——你说基于证据，那好，告诉我证据在哪。

---

## 二、引用元数据如何从原始文档产生

**说话人1: 问题十一，原始 PDF、Word、Excel、HTML 文档进入 RAG 系统后，如何建立来源信息？**

**说话人1: 答，** 入库第一步：上传文件 → 生成 documentId → 计算文件 hash → 记录文件名、类型、上传时间、上传人 → 存入 source_document 表。这是追溯链的起点，后面所有 Chunk 都挂在这个 documentId 下。

**说话人1: 问题十二，文档入库时应该记录哪些基础元数据？**

**说话人1: 答，**

```
documentId          唯一标识
fileName            原始文件名
fileType            PDF / Word / Excel / HTML
fileSize            文件大小
contentHash         文件内容 hash（识别重复和变化）
version             文档版本号
uploadedBy          上传人
uploadedAt          上传时间
effectiveDate       生效日期
expiryDate          失效日期
tenantId            所属租户
sourceType          OEM手册 / 内部规范 / 历史工单
trustLevel          TRUSTED / UNTRUSTED
```

**说话人1: 问题十三，documentId、chunkId、sourceId 分别承担什么职责？**

**说话人1: 答，**

- **sourceId**：原始文件的存储标识（对象存储路径）。
- **documentId**：系统中文档业务记录的 ID，关联 sourceId。
- **chunkId**：文档切块后每个 Chunk 的唯一 ID。

引用时用 chunkId 精确定位到段落，通过 chunkId → documentId → sourceId 追到原始文件。

**说话人1: 问题十四，为什么需要区分业务文档 ID 和向量数据库中的记录 ID？**

**说话人1: 答，** 向量数据库的记录 ID 是物理主键，可能因为重新入库、重建索引而变化。业务 documentId 是稳定的，不随物理存储变化。引用要指向稳定的业务 ID，不能指向会变的物理 ID。

**说话人1: 问题十五，如何记录文档名称、原始文件路径、文档类型、版本、发布时间和更新时间？**

**说话人1: 答，** 全部存在 source_document 表。这些是展示给用户的信息：用户点引用时看到"INV-3000维修手册 v3.2，2026-01-15发布"。

**说话人1: 问题十六，如何记录原始文档的页码、章节标题、段落编号和表格位置？**

**说话人1: 答，** 这些是 Chunk 级别的定位信息，存在 document_chunk 表：`pageNumber`、`sectionPath`（如"第3章/3.2节"）、`paragraphIndex`、`tableIndex`。引用要精确到这些位置。

**说话人1: 问题十七，PDF 解析后如何保留页码与文本片段之间的对应关系？**

**说话人1: 答，** PDF 解析器（如 Apache PDFBox）逐页提取文本时，记录每页的页码。切块时，每个 Chunk 记录它来自哪些页——可能跨页，就记 `startPage` 和 `endPage`。

```
// PDF 解析时保留页码
for (PDFPage page : pdfDocument.getPages()) {
    String text = page.getText();
    int pageNum = page.getPageNumber();
    // 切块时把 pageNum 写进 Chunk Metadata
}
```

**说话人1: 问题十八，Word 文档经过解析后，如何保留标题层级和章节路径？**

**说话人1: 答，** Word 解析时根据样式（Heading 1/2/3）构建章节树。每个段落记录它的章节路径，如 `["维修指南", "故障处理", "E023传感器异常"]`。Chunk 继承这个路径，引用时展示章节位置。

**说话人1: 问题十九，Excel 文档经过解析后，如何保留工作表名称、行号和单元格范围？**

**说话人1: 答，** Excel 按工作表切块，每个 Chunk 记录：`sheetName`、`startRow`、`endRow`、`cellRange`。引用时显示"参数表 / Sheet1 / 第15-18行"。

**说话人1: 问题二十，HTML 文档经过解析后，如何保留页面标题、URL 和章节锚点？**

**说话人1: 答，** HTML 解析时记录 `<title>`、URL、章节标题和锚点 ID。引用时生成带锚点的链接，用户点击直接跳到对应章节。

**说话人1: 问题二十一，为什么仅记录文件名不足以支持可靠的引用追溯？**

**说话人1: 答，** 因为同一文件名可能有多个版本（维修手册v1.pdf、维修手册v2.pdf），也可能内容变了但文件名没变。只记文件名，用户不知道引用的是哪一版、哪一页。

**说话人1: 问题二十二，如何使用文档哈希值识别重复文档和内容变化？**

**说话人1: 答，** 上传文件时算 SHA-256 hash。如果 hash 已存在，说明是重复文件，直接复用已有 documentId。如果文件名相同但 hash 不同，说明是更新版本，创建新版本记录。

**说话人1: 问题二十三，原始文档更新后，如何维护新旧版本之间的关系？**

**说话人1: 答，** source_document 表加 `parentDocumentId` 字段，指向旧版本。新版本入库时标记旧版本为 expired。检索时默认只返回当前生效版本的 Chunk。

**说话人1: 问题二十四，如何设计统一的 Source Metadata 模型，兼容不同类型的原始文档？**

**说话人1: 答，** 基础字段通用（documentId, fileName, version, tenantId），扩展字段用 JSONB 存（PDF 的 pageCount、Word 的 sectionTree、Excel 的 sheetList）。Spring AI 的 Document Metadata 就是 Map<String, Object>，天然支持这种扩展。

---

## 三、Chunk 如何继承原始文档来源

**说话人1: 问题二十五，文档切块为什么不能只保留文本内容而丢失来源信息？**

**说话人1: 答，** 因为切块后的 Chunk 如果不知道自己来自哪份文档、哪一页，检索回来就没法做引用。你只能给用户一段干巴巴的文本，他不知道出处，不敢信。

**说话人1: 问题二十六，Chunk 在创建时应该继承原始文档的哪些 Metadata？**

**说话人1: 答，**

```
documentId        所属文档 ID
fileName          文档名
version           文档版本
tenantId          租户
sourceType        来源类型
trustLevel        可信等级
deviceModel       设备型号（业务标签）
pageNumber        起始页码
endPageNumber     结束页码
sectionPath       章节路径
```

**说话人1: 问题二十七，一个 Chunk 如何关联到唯一的原始文档？**

**说话人1: 答，** 每个 Chunk 的 Metadata 里都带 `documentId`。Chunk 表的 `documentId` 字段加外键关联 source_document 表。

**说话人1: 问题二十八，一个原始文档切分成多个 Chunk 后，如何保持引用关系？**

**说话人1: 答，** 多个 Chunk 共享同一个 documentId。引用时，用户看到的是"来自《维修手册v3.2》第 15 页"，不关心具体是哪个 Chunk——Chunk 是内部检索单元，不是用户视角的单位。

**说话人1: 问题二十九，Chunk 的 chunkId 应该如何生成，才能保证稳定性和可追溯性？**

**说话人1: 答，** 不要用数据库自增 ID，要用内容哈希或确定性生成：`chunkId = hash(documentId + startOffset + endOffset)`。这样同一段文本无论重新切块多少次，chunkId 都不变。引用记录才能长期有效。

**说话人1: 问题三十，为什么不能只依赖 Chunk 在向量数据库中的物理位置作为引用标识？**

**说话人1: 答，** 因为向量数据库的物理 ID 在重建索引、迁移数据时会变。如果引用里存的是物理 ID，重建索引后所有历史引用都指向错误位置。要用稳定的业务 chunkId。

**说话人1: 问题三十一，Chunk Size 和 Chunk Overlap 会如何影响引用范围？**

**说话人1: 答，** Chunk 越大，一个引用覆盖的内容越多，但可能混入无关内容。Chunk 越小，引用越精确，但可能需要多个 Chunk 拼起来才完整。Overlap 会让相邻 Chunk 内容重叠，引用时要注意去重。

**说话人1: 问题三十二，如果一个 Chunk 横跨两个 PDF 页面，应该如何记录页码范围？**

**说话人1: 答，** 记录 `startPage` 和 `endPage`。引用时显示"第 15-16 页"。用户点击时跳转到 startPage。

**说话人1: 问题三十三，如果一个 Chunk 包含多个章节的内容，应该如何记录章节来源？**

**说话人1: 答，** 记录主要章节路径（占比最大的那个章节），或者记录所有涉及的章节。如果一个 Chunk 跨章节太多，说明切分有问题，应该重新调整切分策略。

**说话人1: 问题三十四，如果 Chunk 是由多个原始段落合并生成的，如何保存原始片段之间的映射？**

**说话人1: 答，** 在 Chunk 的 Metadata 里加 `sourceSegments` 数组，记录每个原始段落的偏移量和位置。引用时精确到具体段落。

**说话人1: 问题三十五，如果一个原始段落被拆成多个 Chunk，如何保证每个 Chunk 都能追溯到正确位置？**

**说话人1: 答，** 每个 Chunk 都记录自己的 `startOffset` 和 `endOffset`（在原始文档中的字符偏移）。即使被拆成三段，每段都知道自己从哪开始到哪结束。

**说话人1: 问题三十六，如何区分原始文本、清洗后的文本和最终进入 EmbeddingModel 的文本？**

**说话人1: 答，** 三个层次：

- **原始文本**：PDF 解析出来的原文，保留格式信息。
- **清洗后文本**：去掉页眉页脚、多余空格、重复内容。
- **向量化文本**：实际传给 EmbeddingModel 的文本。

引用定位基于原始文本的偏移量，不是清洗后的。

**说话人1: 问题三十七，为什么引用定位信息不能依赖清洗后文本的字符偏移量直接推算？**

**说话人1: 答，** 因为清洗会删字符、改格式，偏移量全变了。你在清洗后的文本里偏移 500 个字符，对应不到原始 PDF 的哪个位置。必须在清洗前就记录原始偏移量。

**说话人1: 问题三十八，如何通过原始文本偏移量定位 Chunk 对应的原始内容？**

**说话人1: 答，** Chunk 的 Metadata 里存 `originalStartOffset` 和 `originalEndOffset`。需要回看原文时，根据偏移量从原始文档存储中截取对应片段。

**说话人1: 问题三十九，如何处理 PDF OCR、表格抽取和格式转换导致的原始位置偏移？**

**说话人1: 答，** OCR 出来的文本位置精度不如原生 PDF。这种情况要标记"OCR 文档"，引用精度可能到页但不到段落。工程上要在 Metadata 里记录文档质量等级，提醒用户引用精度有限。

**说话人1: 问题四十，为什么 Chunk 的来源 Metadata 应该作为数据契约进行统一管理？**

**说话人1: 答，** 因为切块、检索、组装、后处理、展示，多个环节都要读这些 Metadata。字段名不统一、类型不一致，环节之间就对不上。要定义统一的 Metadata Schema，所有模块遵守。

---

## 四、引用元数据如何进入向量数据库

**说话人1: 问题四十一，Spring AI 的 Document 对象由哪些核心信息组成？**

**说话人1: 答，** Spring AI 的 `org.springframework.ai.document.Document`：

- `id`：文档唯一 ID
- `text`：文本内容
- `metadata`：Map<String, Object>，存所有来源信息
- `embedding`：向量（写入时由 EmbeddingModel 生成）

**说话人1: 问题四十二，Spring AI 1.1.8 中，Document 的文本内容、Metadata 和 ID 分别承担什么职责？**

**说话人1: 答，**

- `id`：稳定标识，引用用这个。
- `text`：传给 EmbeddingModel 和最终 Prompt 的内容。
- `metadata`：来源信息、过滤条件、展示信息，都在这里。

**说话人1: 问题四十三，Document 的 Metadata 如何保存原始文档来源、页码和章节信息？**

**说话人1: 答，**

```
Map<String, Object> metadata = new HashMap<>();
metadata.put("documentId", "doc-001");
metadata.put("fileName", "INV-3000维修手册.pdf");
metadata.put("version", "v3.2");
metadata.put("pageNumber", 15);
metadata.put("sectionPath", "故障处理/E023传感器异常");
metadata.put("deviceModel", "INV-3000");
metadata.put("tenantId", "factory-a");

Document chunk = new Document(text, metadata);
```

**说话人1: 问题四十四，使用 Spring AI VectorStore 写入文档时，Metadata 如何随 Document 一起存储？**

**说话人1: 答，** 调用 `vectorStore.add(List.of(document))` 时，Spring AI 自动把 Document 的 id、text、metadata、embedding 一起写入向量表。pgvector 的话，metadata 存在 JSONB 列里。

**说话人1: 问题四十五，PostgreSQL + pgvector 中，向量记录与原始文档元数据应该如何关联？**

**说话人1: 答，** 两种方案：

- **方案 A**：metadata JSONB 里冗余常用字段（documentId, page, deviceModel），检索时直接过滤。
- **方案 B**：document_chunk 表关联 source_document 表，JOIN 查询完整信息。

实际工程两种结合：高频过滤字段冗余在向量表，完整文档信息存关系表。

**说话人1: 问题四十六，为什么不建议把所有原始文档信息都重复存储在每个 Chunk 中？**

**说话人1: 答，** 因为冗余太多字段，存储浪费、索引膨胀。一个 100KB 的文档切成 50 个 Chunk，每个 Chunk 都存完整文档信息，重复存了 50 份。只存必要的过滤字段和定位字段，完整信息查关系表。

**说话人1: 问题四十七，哪些 Metadata 适合冗余存储在向量表中，哪些适合通过关系表查询？**

**说话人1: 答，**

| 适合冗余在向量表      | 适合关系表查询         |
| --------------------- | ---------------------- |
| documentId（关联用）  | fileName（完整文档名） |
| tenantId（过滤用）    | version 详情           |
| deviceModel（过滤用） | 上传人、上传时间       |
| pageNumber（定位用）  | 文档描述               |
| chunkType（过滤用）   | 审核状态               |

原则：检索时需要过滤的字段冗余，展示时需要的信息走关系表。

**说话人1: 问题四十八，如何设计 source_document、document_chunk 和向量记录之间的关系？**

**说话人1: 答，**

```
source_document (documentId PK, fileName, version, ...)
    │ 1
    │
    │ N
document_chunk (chunkId PK, documentId FK, pageNumber, text, embedding vector)
```

向量列就在 document_chunk 表里，pgvector 扩展直接用。

**说话人1: 问题四十九，如何避免向量数据写入成功，但来源元数据写入失败造成引用断链？**

**说话人1: 答，** 事务一致性：source_document 和 document_chunk 在同一个数据库事务里写入。要么都成功，要么都回滚。不能向量写进去了，文档记录没进去。

**说话人1: 问题五十，如何保证文档、Chunk 和向量数据在更新、删除时保持一致？**

**说话人1: 答，** 级联操作：删文档时，自动删所有 Chunk 和对应的向量记录。更新版本时，旧版本标记 expired，新版本入库。都在事务里完成。

**说话人1: 问题五十一，为什么 Metadata 字段应该有明确的数据类型和命名规范？**

**说话人1: 答，** 因为检索时要做 Metadata 过滤（如 `deviceModel == 'INV-3000'`），类型不对会导致过滤失败。命名统一（驼峰 or 下划线），代码里不会拼错字段名。

**说话人1: 问题五十二，如何处理 Spring AI Document Metadata 中的类型转换和序列化问题？**

**说话人1: 答，** Spring AI 的 Metadata 底层是 Map<String, Object>，写入 pgvector JSONB 时会序列化。注意：数字类型保持 Integer/Long，不要转成 String；布尔值保持 Boolean。检索时 FilterExpression 对类型敏感。

**说话人1: 问题五十三，如何通过数据库约束、外键和索引保障来源数据的完整性？**

**说话人1: 答，**

- `document_chunk.documentId` 外键关联 `source_document.documentId`。
- `document_chunk (tenantId, deviceModel)` 加复合索引，加速过滤。
- JSONB 字段上对常用键加 GIN 索引。

**说话人1: 问题五十四，如何验证向量数据库中的每条记录都能找到对应的原始文档？**

**说话人1: 答，** 定期跑一致性检查：`SELECT COUNT(*) FROM document_chunk c LEFT JOIN source_document d ON c.documentId = d.documentId WHERE d.documentId IS NULL`。查出来的就是断链记录。

---

## 五、检索阶段如何保留引用信息

**说话人1: 问题五十五，用户 Query 进入 RAG 系统后，检索阶段如何获取候选 Chunk？**

**说话人1: 答，** Query → EmbeddingModel 生成向量 → VectorStore.similaritySearch() → 返回 List<Document>。每个 Document 都带着原来写入时的 metadata，包括 documentId、pageNumber、deviceModel 等。

**说话人1: 问题五十六，Spring AI VectorStore.similaritySearch() 返回的 Document 是否能够携带原始 Metadata？**

**说话人1: 答，** 是。返回的每个 Document 对象，metadata 就是入库时写进去的那些字段。你不需要额外查库，直接从 Document.getMetadata() 里拿来源信息。

**说话人1: 问题五十七，SearchRequest 的 Top-K、Similarity Threshold 和 Filter 分别会影响什么？**

**说话人1: 答，**

- `topK`：返回多少条候选。
- `similarityThreshold`：相似度低于多少过滤掉。
- `filterExpression`：Metadata 过滤（租户、设备型号等）。

三者共同决定返回哪些 Chunk。

**说话人1: 问题五十八，为什么检索阶段不能只提取 Chunk 文本而丢弃 Document ID 和 Metadata？**

**说话人1: 答，** 因为后面 Context Assembly、引用映射、后处理校验都要用这些信息。你把 metadata 丢了，后面所有环节都不知道这段文本来自哪，引用链就断了。

**说话人1: 问题五十九，如果使用混合检索，BM25 和向量检索返回的结果如何统一来源信息？**

**说话人1: 答，** 两种检索方式返回的都是 Document 对象，都带 chunkId 和 metadata。合并结果时按 chunkId 去重，metadata 自然就统一了。

**说话人1: 问题六十，如果经过 Reranker 重新排序，如何确保原始 Chunk ID 和来源 Metadata 不丢失？**

**说话人1: 答，** Reranker 只改排序，不改 Document 内容。它接收 List<Document>，输出重新排序后的 List<Document>，metadata 原样保留。只要 Reranker 实现不丢字段就行。

**说话人1: 问题六十一，如果多个检索结果来自同一份原始文档，应该如何合并或保留引用？**

**说话人1: 答，** 保留所有 Chunk（因为内容不同），但展示引用时按 documentId 合并——用户看到"《维修手册v3.2》第 15、22 页"，而不是两个独立引用。

**说话人1: 问题六十二，如果同一份文档存在多个版本，如何避免引用到过期版本？**

**说话人1: 答，** 检索时 filter 加 `expiryDate IS NULL OR expiryDate > now()`，只返回生效版本。旧版本的 Chunk 根本不会被检索到。

**说话人1: 问题六十三，如何在检索阶段完成租户、用户、设备和文档权限过滤？**

**说话人1: 答，** SearchRequest 的 filterExpression 里加：

```
.filterExpression("tenantId == '" + user.getTenantId() + "' && deviceModel in " + user.getAccessibleDevices())
```

下推到数据库 WHERE 子句，越权数据根本不返回。

**说话人1: 问题六十四，为什么检索结果的来源信息应该在进入 Context Assembly 之前完成校验？**

**说话人1: 答，** 因为校验越早越安全。如果检索结果里有缺 documentId 的异常记录，在进入上下文之前就过滤掉，不要让模型读到来源不明的文本。

**说话人1: 问题六十五，如何识别缺失 documentId、chunkId 或来源路径的异常检索结果？**

**说话人1: 答，** 检索后做校验：

```
for (Document doc : results) {
    if (!doc.getMetadata().containsKey("documentId")) {
        log.warn("Missing documentId for chunk: {}", doc.getId());
        continue;  // 跳过异常记录
    }
}
```

**说话人1: 问题六十六，如何防止模型上下文中出现无法追溯来源的孤立文本片段？**

**说话人1: 答，** 上下文组装时，只接受有完整来源信息的 Chunk。没有 documentId、没有 pageNumber 的 Chunk，不进上下文。宁可少给证据，也不给不可追溯的内容。

---

## 六、Context Assembly 如何建立证据映射

**说话人1: 问题六十七，什么是 Context Assembly？它在引用传递链路中承担什么职责？**

**说话人1: 答，** Context Assembly 是把检索回来的多个 Chunk，组装成最终传给模型的 Prompt 上下文。在引用链路里，它负责两件事：给每个证据分配编号（[S1]、[S2]），建立编号和 chunkId 的映射关系。

**说话人1: 问题六十八，为什么 Context Assembly 不应该只是简单地将多个 Chunk 拼接成一个字符串？**

**说话人1: 答，** 因为简单拼接后，你不知道哪段文本对应哪个 chunkId，后面就没法做引用映射。组装时要结构化：每个证据片段带上编号和元数据，不是纯字符串拼接。

**说话人1: 问题六十九，如何将多个检索 Chunk 组装成结构化证据集合？**

**说话人1: 答，**

```
List<Evidence> evidences = new ArrayList<>();
Map<String, String> evidenceMap = new HashMap<>();

for (int i = 0; i < chunks.size(); i++) {
    String refId = "[S" + (i + 1) + "]";
    Evidence e = new Evidence();
    e.setRefId(refId);
    e.setChunkId(chunk.getId());
    e.setDocumentId(chunk.getMetadata().get("documentId"));
    e.setText(chunk.getText());
    e.setPageNumber((Integer) chunk.getMetadata().get("pageNumber"));
    evidences.add(e);
    evidenceMap.put(refId, chunk.getId());
}
```

**说话人1: 问题七十，如何为每个证据片段分配唯一的引用编号？**

**说话人1: 答，** 按顺序编号 [S1]、[S2]、[S3]...。编号在本次请求内唯一，和 chunkId 一一对应。

**说话人1: 问题七十一，为什么应该建立引用编号与原始 chunkId 之间的映射关系？**

**说话人1: 答，** 因为模型输出里写的是 [S1]，不是 chunkId-015。你需要一个映射表，把模型的编号翻译成真实的 chunkId，才能查到对应的文档、页码、链接。

**说话人1: 问题七十二，如何设计 Evidence 对象，保存引用编号、Chunk ID、Document ID、文本和来源信息？**

**说话人1: 答，**

```
public class Evidence {
    private String refId;        // [S1]
    private String chunkId;      // chunk-015
    private String documentId;   // doc-001
    private String fileName;     // 手册名
    private Integer pageNumber;  // 页码
    private String sectionPath;  // 章节路径
    private String text;         // 证据文本
    private double score;        // 相似度分数
}
```

**说话人1: 问题七十三，当多个 Chunk 内容相似或重复时，如何避免引用编号混乱？**

**说话人1: 答，** 组装前先去重：相似度过高的 Chunk 合并成一个证据。否则模型会看到两段几乎一样的内容，引用编号也重复。

**说话人1: 问题七十四，当 Context Assembly 对 Chunk 进行截断时，如何同步调整引用范围？**

**说话人1: 答，** 如果一个 Chunk 太长被截断，记录实际传给模型的是哪一部分。引用时说明"节选"，不要让用户以为引用覆盖了整个 Chunk。

**说话人1: 问题七十五，当上下文预算不足，需要删除部分 Chunk 时，如何同步删除对应的引用映射？**

**说话人1: 答，** 删除 Chunk 时，同时从 evidenceMap 里删掉对应的编号。后面的证据重新编号（因为 [S3] 删了，原来的 [S4] 变成 [S3]）。映射表始终和实际传给模型的内容一致。

**说话人1: 问题七十六，为什么不能让模型自行生成不存在的引用编号？**

**说话人1: 答，** 因为模型可能幻觉。它可能编一个 [S9]，但你只传了 5 个证据。后处理校验时要检查：模型输出里的所有编号，都必须在本次请求的 evidenceMap 里存在。

**说话人1: 问题七十七，如何保证 Prompt 中的证据编号与实际传入的证据内容严格一致？**

**说话人1: 答，** 组装 Prompt 时，证据编号列表和 evidenceMap 是同一份数据生成的。Prompt 里写"以下是参考证据：[S1] ... [S2] ..."，evidenceMap 里也是 S1→chunk-015, S2→chunk-022。同一个数据源，不会不一致。

**说话人1: 问题七十八，为什么应该将证据内容和证据元数据分开管理？**

**说话人1: 答，** 证据内容是传给模型的文本；元数据是内部追溯用的。模型只需要看到文本和编号，不需要知道 tenantId、trustLevel 这些内部字段。分开存，模型 Prompt 更干净，内部信息也不泄露。

**说话人1: 问题七十九，如何处理证据之间存在冲突、重复或版本不一致的情况？**

**说话人1: 答，** 组装时做冲突检测：如果两个 Chunk 来自不同版本的同一份文档，只保留最新版。如果内容重复，合并。不要把矛盾的证据同时塞给模型。

**说话人1: 问题八十，如何保证上下文组装后的每条证据仍然可以追溯到原始文档？**

**说话人1: 答，** 每条 Evidence 都带着 documentId 和 chunkId。从 Evidence 出发，能查到 documentId 对应的 source_document 记录，再追到原始文件。链路完整。

---

## 七、Prompt 与模型生成如何传递引用

**说话人1: 问题八十一，如何在 Prompt 中明确告诉模型只能依据提供的证据回答？**

**说话人1: 答，** System Prompt 里写清楚规则：

```
你是一个维修助手。只能根据以下提供的参考证据回答用户问题。
证据中没有的信息，不要编造。
每个事实性陈述后面，用 [S编号] 标注引用来源。
如果证据不足以回答问题，请明确说明"根据现有资料无法确认"。
```

**说话人1: 问题八十二，如何将 [S1]、[S2] 等引用标识注入模型上下文？**

**说话人1: 答，** User Prompt 里结构化展示证据：

```
参考证据：
[S1] E023 是温度传感器信号异常，表示传感器读数超出正常范围。
[S2] 处理步骤：1. 停机 2. 断电 3. 检查传感器连接器 4. 恢复后测试。

用户问题：INV-3000 报 E023 怎么处理？
```

**说话人1: 问题八十三，为什么应该要求模型在事实性陈述后标记引用编号？**

**说话人1: 答，** 因为没有引用，你不知道哪句话是基于证据的，哪句话是模型自己编的。引用标记把"模型说的"变成"模型根据证据 S1 说的"，可验证。

**说话人1: 问题八十四，为什么引用编号只是模型生成的候选标记，而不是已经验证的真实引用？**

**说话人1: 答，** 因为模型可能犯错：引用不存在的编号、引用错了编号、引用的内容不支持结论。模型输出的引用只是"它认为自己用了哪个证据"，后处理要校验这个"认为"对不对。

**说话人1: 问题八十五，模型可能生成哪些错误引用？**

**说话人1: 答，** 四类：

1. **引用不存在的编号**：证据只有 5 个，模型写了 [S9]。
2. **引用正确编号但内容不支持结论**：[S1] 讲故障现象，模型拿来当维修步骤引用。
3. **张冠李戴**：[S1] 是 A 手册，模型说成 B 手册。
4. **遗漏引用**：结论是对的，但没标引用。

**说话人1: 问题八十六，如何处理模型引用了不存在的 [S3]？**

**说话人1: 答，** 后处理校验：扫描模型输出里的所有 [Sx]，不在 evidenceMap 里的就是无效引用。删掉这个标记，或者标记为"引用无效"。

**说话人1: 问题八十七，如何处理模型引用了正确编号，但引用内容实际上不支持该结论？**

**说话人1: 答，** 这是语义层面的错误，简单正则查不出来。需要用 LLM-as-Judge 或 NLI 判断：结论能不能从 Sx 的文本里推出来？推不出来就标记为"引用不支持"。

**说话人1: 问题八十八，如何避免模型将某个证据中的内容错误归因于另一份文档？**

**说话人1: 答，** 后处理时根据 evidenceMap 把编号翻译成真实文档名。模型说"根据 S1"，系统翻译成"根据《INV-3000维修手册v3.2》第15页"。翻译过程是服务端做的，模型没有机会写错文档名。

**说话人1: 问题八十九，如何处理模型生成答案时遗漏引用标记的情况？**

**说话人1: 答，** 如果关键事实结论没有引用，后处理可以：自动补全（根据语义匹配最相关的证据）或者标记为"未标注来源"。高风险维修建议不能没有引用。

**说话人1: 问题九十，为什么不能直接信任模型输出的文件名、页码、URL 和文档 ID？**

**说话人1: 答，** 因为这些都是模型生成的文本，可能编。模型说"根据《维修手册v2.1》第 20 页"，实际它看到的证据是 v3.2 第 15 页。文档名和页码必须由服务端根据 evidenceMap 翻译，不能用模型输出的。

**说话人1: 问题九十一，如何通过结构化输出约束模型返回答案和引用编号？**

**说话人1: 答，** 用 Spring AI 的 BeanOutputConverter，让模型输出 JSON：

```
{
  "answer": "E023 是传感器异常 [S1]，需断电检查 [S2]",
  "citations": ["S1", "S2"]
}
```

结构化输出比纯文本更容易后处理校验。

**说话人1: 问题九十二，如何让模型将多个事实性结论分别关联到不同证据？**

**说话人1: 答，** Prompt 里要求"每个事实陈述都要标注引用"。模型会自然地把不同结论对应到不同证据。如果它把所有结论都标一个 [S1]，说明那个证据确实覆盖了所有内容，或者模型偷懒了。

**说话人1: 问题九十三，如果模型生成的结论需要多个 Chunk 共同支持，应该如何表达多证据引用？**

**说话人1: 答，** 允许模型写"[S1][S2]"，表示这个结论同时参考了两个证据。后处理时把两个引用都列出来。

**说话人1: 问题九十四，如果模型无法找到充分证据，应该如何要求其拒答或明确说明不确定性？**

**说话人1: 答，** Prompt 里明确规则："如果证据不足以回答问题，请说'根据现有资料无法确认具体原因，建议联系设备厂商技术支持'。不要编造答案。"

---

## 八、后处理与引用校验

**说话人1: 问题九十五，为什么引用校验应该发生在模型生成之后、响应返回之前？**

**说话人1: 答，** 因为模型输出是不可信的。你不能把没校验过的引用直接展示给用户——那等于把模型的幻觉当真。后处理是最后一道关，校验完了才能返回。

**说话人1: 问题九十六，如何从模型响应中提取引用编号？**

**说话人1: 答，** 正则匹配 `\[S\d+\]`：

```
Pattern pattern = Pattern.compile("\\[S(\\d+)\\]");
Matcher matcher = pattern.matcher(modelOutput);
Set<String> citedRefs = new HashSet<>();
while (matcher.find()) {
    citedRefs.add(matcher.group());
}
```

**说话人1: 问题九十七，如何验证模型使用的引用编号是否存在于本次请求的 Evidence Map 中？**

**说话人1: 答，**

```
for (String ref : citedRefs) {
    if (!evidenceMap.containsKey(ref)) {
        log.warn("Model cited non-existent reference: {}", ref);
        // 标记或删除无效引用
    }
}
```

**说话人1: 问题九十八，如何验证引用编号是否属于当前用户有权访问的证据集合？**

**说话人1: 答，** Evidence Map 本身就是基于用户权限过滤后的检索结果构建的。如果 evidenceMap 里的证据都是用户有权看的，那引用它们自然也是有权的。但要二次确认：检索时的权限过滤没出错。

**说话人1: 问题九十九，如何避免模型伪造原始文档名称、页码和 URL？**

**说话人1: 答，** 文档名、页码、链接全部由服务端根据 evidenceMap 生成，模型输出里不出现这些。模型只负责写 [S1]，服务端负责把 [S1] 翻译成真实文档名。模型没有机会伪造。

**说话人1: 问题一百，如何通过服务端映射关系恢复真实文档来源信息？**

**说话人1: 答，** CitationResolver：

```
public CitationDTO resolve(String refId) {
    String chunkId = evidenceMap.get(refId);
    DocumentChunk chunk = chunkRepository.findById(chunkId);
    SourceDocument doc = sourceDocumentRepository.findById(chunk.getDocumentId());
    return new CitationDTO(
        doc.getFileName(),
        doc.getVersion(),
        chunk.getPageNumber(),
        chunk.getSectionPath(),
        buildDocumentUrl(doc, chunk)
    );
}
```

**说话人1: 问题一百零一，什么是 Citation Precision？**

**说话人1: 答，** 引用精确率：模型引用的所有来源中，真正支持对应结论的比例。

$$Citation\ Precision = \frac{\text{有效引用数}}{\text{模型引用总数}}$$

**说话人1: 问题一百零二，什么是 Citation Recall？**

**说话人1: 答，** 引用召回率：答案中需要引用的事实，有多少被正确引用了。

$$Citation\ Recall = \frac{\text{被正确引用的事实数}}{\text{应该引用的总事实数}}$$

**说话人1: 问题一百零三，如何判断引用是否真正支持其对应的答案陈述？**

**说话人1: 答，** 这是语义判断，需要：

1. 把答案拆成原子事实。
2. 对每个事实，对照引用的证据文本。
3. 判断能不能从证据推出结论。

工程上用 LLM-as-Judge 做初筛，关键场景人工复核。

**说话人1: 问题一百零四，如何使用 NLI 或 LLM-as-a-Judge 辅助评估引用支持关系？**

**说话人1: 答，** NLI（自然语言推断）模型判断：前提（证据文本）能不能推出假设（答案结论）。LLM Judge 更灵活，给它 Prompt 让它判断"答案中的结论是否被引用的证据支持"。

**说话人1: 问题一百零五，为什么引用编号有效，不代表引用内容一定真实可靠？**

**说话人1: 答，** 因为编号存在 ≠ 内容支持。模型可能正确引用了 [S1]，但 [S1] 讲的是 A，模型拿它当 B 的证据。编号合法只是格式对，语义支持是另一回事。

**说话人1: 问题一百零六，如何处理无效引用、缺失引用和不支持答案的引用？**

**说话人1: 答，**

| 问题类型           | 处理方式                                 |
| ------------------ | ---------------------------------------- |
| 无效编号（不存在） | 删除标记，或提示"引用无效"               |
| 缺失引用（没标）   | 自动匹配补全，或标记"未标注来源"         |
| 不支持引用         | 标记"引用不支持结论"，高风险场景降级答案 |

**说话人1: 问题一百零七，当答案中的某个结论没有任何证据支持时，应该删除结论、降低置信度还是拒绝回答？**

**说话人1: 答，** 看风险等级：

- 低风险场景：保留结论，但标记"未找到直接依据"。
- 高风险场景（维修建议）：删除结论，或者降级为"建议联系技术支持"。

**说话人1: 问题一百零八，如何避免后处理程序仅检查引用格式，却没有检查引用的语义支持关系？**

**说话人1: 答，** 格式校验（编号存在性）是第一层，语义校验（内容是否支持）是第二层。两层都要做，不能只查格式。很多工程实现只查了编号存不存在，没查内容对不对——这是不够的。

---

## 九、最终响应与前端展示

**说话人1: 问题一百零九，RAG 最终响应应该包含哪些引用字段？**

**说话人1: 答，**

```
{
  "answer": "E023 是温度传感器信号异常，需断电检查连接器",
  "citations": [
    {
      "refId": "S1",
      "documentName": "INV-3000维修手册.pdf",
      "version": "v3.2",
      "pageNumber": 15,
      "sectionPath": "故障处理/E023传感器异常",
      "url": "/api/documents/doc-001?page=15"
    }
  ]
}
```

**说话人1: 问题一百一十，为什么不应该直接将内部 PostgreSQL 主键或向量记录 ID 暴露给前端？**

**说话人1: 答，** 因为内部 ID 会暴露系统结构，可能被用于攻击（如尝试访问其他 ID 的文档）。而且内部 ID 不稳定，重建索引会变。前端只需要业务层面的信息：文档名、页码、链接。

**说话人1: 问题一百一十一，如何设计统一的 Citation DTO？**

**说话人1: 答，** 前面 JSON 示例就是。核心字段：refId、documentName、version、pageNumber、sectionPath、url。

**说话人1: 问题一百一十二，Citation DTO 应该包含哪些字段？**

**说话人1: 答，** 看用户需要什么：文档名、版本、页码、章节、可点击链接。不需要暴露 documentId、chunkId 这些内部标识。

**说话人1: 问题一百一十三，如何将模型返回的引用编号转换成前端可展示的真实来源信息？**

**说话人1: 答，** 后处理时，CitationResolver 根据 evidenceMap 把每个 refId 翻译成 CitationDTO。模型输出里的 [S1] 标记保留在 answer 文本里，citations 数组里放对应的来源信息。前端渲染时把 [S1] 变成可点击的上标。

**说话人1: 问题一百一十四，如何让前端点击引用后定位到原始 PDF 的具体页码？**

**说话人1: 答，** URL 里带页码参数：`/api/documents/doc-001/view?page=15`。前端 PDF 阅读器打开时跳转到第 15 页。

**说话人1: 问题一百一十五，如果引用来自 Word、Excel 或 HTML，应该如何设计不同的定位方式？**

**说话人1: 答，**

- Word：链接到在线预览，带章节锚点。
- Excel：链接到对应工作表和行号。
- HTML：带 `#section-id` 锚点。

每种文档类型有自己的定位参数，统一在 URL 里传递。

**说话人1: 问题一百一十六，如何处理用户没有原始文档访问权限，但模型上下文中曾经存在该文档的情况？**

**说话人1: 答，** 这是个安全问题：如果用户没权限看原文档，那这个文档根本不应该进上下文。检索层权限过滤要拦住。万一漏了，后处理时发现引用的文档用户无权访问，要删掉这条引用，或者标记为"来源不可查看"。

**说话人1: 问题一百一十七，为什么引用链接在响应返回时仍然需要执行权限校验？**

**说话人1: 答，** 因为用户权限可能变了（比如会话中途被降级），或者引用的文档刚好过期了。不能因为检索时有权限就默认返回时也有——最后再校验一次。

**说话人1: 问题一百一十八，如何处理原始文档已经删除、归档或版本失效的引用？**

**说话人1: 答，** 后处理时查 documentId 对应的文档状态。如果已删除/已归档，引用标记为"原始文档已归档"，链接指向归档系统或者不可点击。不要给用户一个 404 的链接。

**说话人1: 问题一百一十九，如何保证 API 响应中的答案、引用列表和引用编号之间保持一致？**

**说话人1: 答，** 同一次请求里，answer 文本中的编号和 citations 数组由同一个后处理流程生成。处理完后做一致性检查：answer 里的每个编号，citations 里都有对应条目。

**说话人1: 问题一百二十，如何通过统一响应结构支持多个引用、多个来源和多条答案结论？**

**说话人1: 答，** citations 是数组，可以放多个。answer 文本里可以有多个 [Sx] 标记。一条结论引用多个证据也支持（[S1][S2]）。

---

## 十、Spring AI 工程实现与生产治理

**说话人1: 问题一百二十一，在 Spring AI 1.1.8 中，如何利用 Document 和 Metadata 实现来源信息传递？**

**说话人1: 答，** 从入库到检索到组装，全程用 Document 对象传递，metadata 里带来源信息。Spring AI 的设计就是 Document 作为统一载体，metadata 是 Map，你加什么字段它都带着。

**说话人1: 问题一百二十二，如何在 DocumentReader 阶段建立原始文档与解析结果之间的映射？**

**说话人1: 答，** 自定义 DocumentReader，读取文件时生成 documentId，把 documentId 写进每个解析出来的 Document 的 metadata。

```
public class SecureDocumentReader implements DocumentReader {
    private final String documentId;
    private final File originalFile;

    @Override
    public List<Document> read() {
        // 解析文件，每个 Document 的 metadata 里写入 documentId
    }
}
```

**说话人1: 问题一百二十三，如何在 DocumentTransformer 或自定义切块逻辑中继承来源 Metadata？**

**说话人1: 答，** Spring AI 的 TokenTextSplitter 在切块时自动继承父 Document 的 metadata。如果是自定义切块，要手动把父 Document 的 metadata 复制到子 Chunk。

```
Document chunk = new Document(chunkText);
chunk.getMetadata().putAll(parentDoc.getMetadata());  // 继承
chunk.getMetadata().put("chunkId", chunkId);          // 加自己的
```

**说话人1: 问题一百二十四，如何在 VectorStore.add() 前检查 Document 的来源信息是否完整？**

**说话人1: 答，** 写入前校验：

```
for (Document doc : documents) {
    if (!doc.getMetadata().containsKey("documentId")) {
        throw new IllegalArgumentException("Missing documentId metadata");
    }
}
vectorStore.add(documents);
```

**说话人1: 问题一百二十五，如何在检索服务中封装 Document 到 Evidence 对象的转换？**

**说话人1: 答，** 写一个专用的 Assembler，不要在业务代码里到处手动解析 metadata。

```
@Component
public class EvidenceAssembler {
    public List<Evidence> toEvidences(List<Document> docs) { ... }
    public Map<String, String> buildEvidenceMap(List<Evidence> evidences) { ... }
}
```

**说话人1: 问题一百二十六，如何避免业务代码在多个地方重复解析 Metadata？**

**说话人1: 答，** 统一在 EvidenceAssembler 里解析一次，后面业务代码直接用 Evidence 对象，不再碰 Document.getMetadata()。字段解析只做一遍。

**说话人1: 问题一百二十七，如何设计统一的 CitationResolver，负责引用编号到真实来源的映射？**

**说话人1: 答，** 前面示例过。输入 refId，输出 CitationDTO。内部查 evidenceMap + 查数据库。所有引用翻译都走这个服务，不各写各的。

**说话人1: 问题一百二十八，如何设计 EvidenceAssembler，将检索结果组装成可追溯的模型上下文？**

**说话人1: 答，** 职责：

1. 接收检索回来的 List<Document>。
2. 按相关性排序、去重。
3. 分配 [S1]、[S2] 编号。
4. 构建 evidenceMap。
5. 生成 Prompt 里的证据文本块。

**说话人1: 问题一百二十九，如何设计 CitationValidator，校验模型生成的引用是否合法？**

**说话人1: 答，** 职责：

1. 从模型输出提取所有 [Sx] 编号。
2. 检查每个编号在 evidenceMap 里存在。
3. 检查引用的文档用户有权访问。
4. （可选）用 LLM Judge 检查语义支持。
5. 返回校验结果和修正后的引用列表。

**说话人1: 问题一百三十，如何让引用传递逻辑与具体 ChatModel、EmbeddingModel 和 VectorStore 实现解耦？**

**说话人1: 答，** 引用逻辑基于 Document 和 Evidence 对象，不依赖具体模型实现。换 ChatModel 不影响引用链路，换 VectorStore 也不影响。接口层抽象，实现层替换。

**说话人1: 问题一百三十一，当使用流式生成时，如何处理答案内容和引用标记尚未完整生成的情况？**

**说话人1: 答，** 流式模式下，[S1] 是一个字一个字吐出来的。后处理校验不能在流式中途做——要等流结束后统一校验。前端流式展示时，引用标记先按纯文本渲染，流结束后再把 [S1] 替换成可点击的链接。

**说话人1: 问题一百三十二，如何避免流式响应先向前端发送了错误引用，后续却无法撤回？**

**说话人1: 答，** 流式过程中只发文本，引用链接在流结束后单独发一个 citations 数组。前端先展示纯文本，流结束后再把引用渲染成可点击的上标。这样就不会"先发了错的再撤回"。

**说话人1: 问题一百三十三，如何通过 TraceId 追踪原始文档、Chunk、检索结果、模型输出和最终引用？**

**说话人1: 答，** 一次请求一个 TraceId，贯穿所有日志：

```
[TRACE-abc] Retrieval: returned chunk-015, chunk-022
[TRACE-abc] Assembly: evidenceMap={S1→chunk-015, S2→chunk-022}
[TRACE-abc] Model output: cited [S1][S2]
[TRACE-abc] Citation resolved: S1→doc-001 p15, S2→doc-001 p22
```

出问题时按 TraceId 查完整链路。

**说话人1: 问题一百三十四，如何记录每次回答实际使用的文档版本、Chunk ID 和引用映射？**

**说话人1: 答，** 存到 `rag_request_trace` 表：requestId、query、retrievedChunkIds、usedChunkIds、citationMap、modelVersion、kbVersion。方便事后审计和问题排查。

**说话人1: 问题一百三十五，当知识库更新或重新切块时，如何保证历史回答仍然可以追溯当时使用的证据版本？**

**说话人1: 答，** 每次回答记录当时的 chunkId 和 documentVersion。即使知识库后来更新了，历史 trace 里存的是当时的快照，你可以查到"当时回答用的是 v3.2 版本的第 15 页"。

**说话人1: 问题一百三十六，如何建立引用链路的自动化测试，覆盖来源丢失、编号错配、引用伪造和权限变化？**

**说话人1: 答，** 测试用例：

1. 入库后查 documentId 是否完整写入 metadata。
2. 检索后 metadata 是否保留。
3. 组装后 evidenceMap 是否和实际证据一致。
4. 模型输出假编号，校验器是否拦住。
5. 引用无权限文档，是否被过滤。

---

## 十一、制造设备故障知识库实战与面试表达

**说话人1: 问题一百三十七，假设用户询问某设备的 E102 故障，系统检索到维修手册和历史工单，如何将两份资料的引用传递到最终答案？**

**说话人1: 答，** 检索回来两个 Chunk：[S1] 是维修手册第 22 页，[S2] 是历史工单 WO-1234。组装时分配编号，Prompt 里告诉模型两个证据。模型回答"E102 是通讯故障 [S1]，上次类似情况重新插拔通讯线解决 [S2]"。后处理校验两个编号都存在，翻译成 CitationDTO：S1→手册 v3.2 第 22 页，S2→历史工单 WO-1234。

**说话人1: 问题一百三十八，假设一个维修步骤由三个 Chunk 共同提供，如何让最终答案准确引用这三个证据？**

**说话人1: 答，** 三个 Chunk 分别是步骤 1、步骤 2、步骤 3。模型回答时每个步骤后面标对应的 [S1]、[S2]、[S3]。后处理校验三个编号都合法。前端展示时，用户点每个步骤的引用，跳到对应手册页。

**说话人1: 问题一百三十九，假设模型引用了正确的手册，但页码错误，应该在哪个环节发现和修复？**

**说话人1: 答，** 页码根本不是模型生成的——是 CitationResolver 根据 evidenceMap 里的 chunkId 查出来的。模型只写 [S1]，服务端翻译出真实页码。模型没有机会写错页码。这就是为什么不能让模型自己写页码。

**说话人1: 问题一百四十，假设模型生成了一个真实存在的文档 ID，但该文档不支持当前结论，如何处理？**

**说话人1: 答，** 这是语义层面的错误。格式校验查不出来（文档 ID 是真的），要用语义校验（LLM Judge 判断结论能不能从证据推出来）。推不出来就标记为"引用不支持"，高风险场景降级答案。

**说话人1: 问题一百四十一，假设检索到的是旧版维修手册，而知识库中已有新版手册，如何避免返回过期引用？**

**说话人1: 答，** 检索时 filter 加 `expiryDate IS NULL`，旧版 Chunk 根本不返回。Context Assembly 阶段再校验一遍：如果新旧版本同时召回，只保留最新版。

**说话人1: 问题一百四十二，假设用户只能访问 A 工厂资料，但模型引用了 B 工厂的维修记录，应该在哪些环节阻断？**

**说话人1: 答，** 三层：

1. 检索层：tenantId 过滤，B 工厂的 Chunk 根本不返回。
2. Context Assembly：二次校验每个证据的 tenantId。
3. 后处理：引用翻译时再查一次用户权限。

**说话人1: 问题一百四十三，假设模型回答了正确的维修建议，却没有提供任何引用，系统应该如何处理？**

**说话人1: 答，** 后处理检测到 answer 里没有 [Sx] 标记。自动匹配：根据语义找到最相关的证据 Chunk，补一个引用。如果匹配不到，标记为"未找到直接依据"。高风险维修建议不能没有引用。

**说话人1: 问题一百四十四，假设模型引用了正确来源，但该来源本身包含错误维修数据，引用机制能否保证答案正确？**

**说话人1: 答，** 不能。引用机制只能保证"答案来自哪个文档"，不能保证"那个文档的内容是对的"。文档本身有错误，引用再准确也是错答案。文档质量是另一个问题，要靠知识库治理（人工审核、版本管理、反馈纠错）解决。

**说话人1: 问题一百四十五，如果让你从零实现一套 RAG 引用链路，你会如何设计原始文档、Chunk、Metadata、Evidence Map、Citation DTO 和校验流程？**

**说话人1: 答，** 五层设计：

1. **原始文档**：source_document 表，记录 documentId、文件名、版本、hash、有效期。
2. **Chunk**：document_chunk 表，chunkId 关联 documentId，带页码、章节、设备型号。
3. **Metadata 传递**：Spring AI Document 对象全程携带 metadata，切块时继承。
4. **Evidence Map**：Context Assembly 时给每个证据分配 [Sx] 编号，建立编号→chunkId 映射。
5. **后处理校验**：提取模型引用编号 → 校验存在性和权限 → CitationResolver 翻译成 CitationDTO → 一致性检查 → 返回前端。

**说话人1: 问题一百四十六，如果面试官问"RAG 如何保证引用从原始文档一直传递到最终响应"，你如何结合 Spring AI 1.1.8 和 PostgreSQL/pgvector 解释完整实现？**

**说话人1: 答，** 核心讲三点：

第一，**Metadata 全程携带**。从 PDF 解析时生成 documentId，切块时继承到每个 Chunk，写入 pgvector 时存在 JSONB metadata 列。检索返回时 Document 对象自带 metadata。Spring AI 的 Document 是统一载体。

第二，**Evidence Map 做编号翻译**。Context Assembly 把检索回来的 Chunk 编号为 [S1]、[S2]，建立编号和 chunkId 的映射。Prompt 让模型引用编号，而不是让模型自己写文档名——模型只说 [S1]，服务端翻译成真实来源。

第三，**后处理校验兜底**。模型输出后，校验引用编号是否存在、用户有没有权限、内容是否支持结论。CitationResolver 把编号翻译成 CitationDTO，包含文档名、版本、页码、可点击链接。

关键设计原则：**模型不生成引用内容，只引用编号；服务端负责翻译和校验。** 这样就避免了模型伪造文档名、页码、URL 的问题。

---

## 面试总结：引用链路的核心逻辑

8 句话讲清楚：

1. **引用不是模型编的，是从原始文档一路带过来的。** PDF 入库 → 切块 → 向量存储 → 检索 → 组装，每一步都带着来源 Metadata。
2. **模型只负责写 [S1]，不负责写文档名和页码。** 模型写文档名一定会幻觉，服务端根据编号翻译才可靠。
3. **Evidence Map 是核心枢纽。** 它连接了模型输出的编号和真实的 chunkId，是引用链路的翻译层。
4. **后处理校验是最后一道关。** 编号存在性、用户权限、语义支持，三层校验都过了才能展示。
5. **引用要能跳到原始文档具体位置。** 不是给个文件名就完了，要精确到页码、章节、行号。
6. **权限校验要贯穿全程。** 检索时过滤、组装时校验、翻译时再查一次——多层防越权。
7. **文档本身错了，引用再准也没用。** 引用机制保证"答案来自哪"，不保证"那个来源是对的"。文档质量靠知识库治理。
8. **流式场景下引用要延迟渲染。** 流结束后再把编号替换成可点击链接，不要中途发了错的再撤回。

---

## 参考资料

1. **Spring AI Reference Documentation | Spring AI 参考文档**
[https://docs.spring.io/spring-ai/reference/](https://docs.spring.io/spring-ai/reference/)
2. **Document API | Spring AI Document API（文档对象接口）**
[https://docs.spring.io/spring-ai/reference/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)
3. **Retrieval Augmented Generation (RAG) | 检索增强生成**
[https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
4. **PostgreSQL Documentation | PostgreSQL 官方文档**
[https://www.postgresql.org/docs/](https://www.postgresql.org/docs/)
5. **pgvector | PostgreSQL 向量相似度搜索扩展**
[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)
6. **RAGAS Documentation | RAGAS 评估框架文档**
[https://docs.ragas.io/en/stable/](https://docs.ragas.io/en/stable/)
7. **OWASP Top 10 for LLM Applications | OWASP 大语言模型应用十大安全风险**
[https://genai.owasp.org/llm-top-10/](https://genai.owasp.org/llm-top-10/)