# D27 PDF 解析与来源定位功能实现全量说明

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 迭代名称 | Day 27：PDF Word 解析与来源元数据 |
| 项目根目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D27` |
| 基础模块 | `D27_PDF_WORD` |
| 完成日期 | 2026-11-09 |
| 核心目标 | 复用文档导入入口，实现 PDF 物理页面级文本提取、documentId 关联和来源追溯 |

## 2. 目标与背景

D26 式文档导入已经能够保存原始文件并提取整份文本，但整文结果丢失了页面来源，无法回答“这段内容来自 PDF 第几页”。D27 在既有登记、导入、状态、去重和异常处理基础上完成以下工作：

- 使用与 Spring AI 1.1.8 兼容的 PDFBox 3.0.7 逐页解析 PDF。
- 新增页面级提取结果，保存物理页码、页面文本、字符数和空白页标记。
- 每页通过 `documentId` 关联原始文档，不从内容猜测来源。
- 文档来源、标题、版本继续使用既有 `knowledge_document` 元数据。
- 空文本、损坏 PDF、扫描型 PDF、不支持格式均明确失败，不产生伪成功结果。
- Word `.docx` 作为可选扩展完成整文文本提取，不伪造 Word 页码。

## 3. 项目架构与 Schema 分析

### 3.1 实际技术栈

| 组件 | 版本 |
|---|---|
| JDK | 21.0.7 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| Apache PDFBox | 3.0.7 |
| Apache Tika | 3.3.1 |
| Apache POI | 5.5.1，Tika 传递 |
| MyBatis-Plus | 3.5.17 |

### 3.2 原有数据职责

| 对象/表 | 职责 |
|---|---|
| `knowledge_document` | 来源、标题、版本、导入状态、文件摘要、错误信息 |
| `knowledge_document_content` | 原始文件字节和非 PDF 兼容整文文本 |
| `KnowledgeDocumentService` | 登记、导入、失败收口和查询编排 |
| `KnowledgeDocumentParser` | 文件识别、校验、文本提取 |

原实现使用 Tika 把整个 PDF 读为一个字符串，只能满足全文提取，不能满足页面边界和物理页码追溯。

## 4. PDF 解析器选型与版本兼容性

项目已经存在：

```text
org.springframework.ai:spring-ai-pdf-document-reader:1.1.8
  -> org.apache.pdfbox:pdfbox:3.0.7
```

因此没有引入第二套 PDF 引擎，也没有默认使用“最新版”。D27 直接使用 PDFBox 3.0.7 的 `Loader`、`PDDocument` 和 `PDFTextStripper`。根 POM 将该已验证版本集中管理，子模块只声明依赖用途。

实际验证：

- `mvn dependency:tree` 可见 PDFBox 3.0.7。
- `mvn -DskipTests compile` 成功。
- `mvn -DskipTests test-compile` 成功。
- `Version.getVersion()` 实际返回 `3.0.7`。
- 真实 11 页 Canon PDF 完整解析成功。

## 5. Maven 依赖变更

根 POM 新增统一版本属性：

```xml
<pdfbox.version>3.0.7</pdfbox.version>
```

`dependencyManagement` 固定：

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>${pdfbox.version}</version>
</dependency>
```

模块 POM 显式声明直接使用，不写散落版本：

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
```

未升级 Spring Boot、Spring AI、Tika 或 MyBatis-Plus。Word 复用 Tika 已传递的 POI 5.5.1，没有新增 Word 生产依赖。

## 6. PDF 文件识别与安全校验

导入继续复用：

```text
POST /api/v1/knowledge/documents
POST /api/v1/knowledge/documents/{documentId}/import
GET  /api/v1/knowledge/documents/{documentId}
```

进入解析前依次执行：

1. 文件名和扩展名白名单校验。
2. 零字节校验。
3. Multipart 与业务层双层文件大小限制。
4. 读取原始字节并计算 SHA-256。
5. Tika 内容类型检测，拒绝扩展名伪装。
6. PDF 仅接受检测结果 `application/pdf`。

新增 `.docx` 白名单，但 `.pdf` 和 `.docx` 使用不同解析路径。

## 7. 页面级文本提取实现

核心实现：

```java
try (PDDocument document = Loader.loadPDF(file.content())) {
    int pageCount = document.getNumberOfPages();
    for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        String extracted = stripper.getText(document);
        boolean blank = !StringUtils.hasText(extracted);
        pages.add(new ExtractedPage(
                pageNumber,
                blank ? "" : extracted.strip(),
                blank));
    }
}
```

设计要点：

- 每次只设置一个起止页，避免跨页字符串粘连。
- 页面序号由 PDFBox 页面树顺序生成，范围从 1 到总页数。
- 空白页也生成页面对象，内容为空且 `blankPage=true`。
- 如果全部页面均无文本，整体失败并明确提示扫描型 PDF 需要 OCR。
- 页面总字符数执行 `max-parsed-chars` 限制。
- PDF 总页数执行 `max-pdf-pages` 限制，默认 2000。
- PDF 页面文本不写入 `knowledge_document_content.parsed_text`，而是进入独立页面表。

## 8. 数据模型与来源元数据

新增 `knowledge_document_page`：

| 字段 | 含义 |
|---|---|
| `document_id` | 关联原始 `KnowledgeDocument` |
| `page_number` | PDF 物理页面序号，从 1 开始 |
| `content` | 该页提取文本 |
| `character_count` | 页面字符数 |
| `blank_page` | 是否为空白页 |
| `parser_name` | 实际解析器名称 |
| `parser_version` | 实际解析器版本 |
| `updated_at` | 页面提取结果更新时间 |

唯一键为 `(document_id, page_number)`，防止同一文档重复保存同一物理页。

来源、标题和版本不重复造字段，而是保留在 `knowledge_document`，页面接口通过同一 `documentId` 返回：

```text
documentId -> knowledge_document(source,title,version,status)
           -> knowledge_document_page(page_number,content,...)
```

## 9. documentId 与解析结果关联

- `documentId` 由登记阶段生成并全局唯一。
- 原始文件行、页面结果行均使用同一 `documentId`。
- 页面查询不使用标题、文件名或正文哈希猜测关联。
- 文档完成后不允许同 `documentId` 替换为不同文件。
- 新版本应登记新的文档记录并保留版本字段；不同记录不会混用页面结果。
- 服务端读取页面时按 `page_number ASC` 排序，不依赖数据库自然插入顺序。

## 10. 页面查询接口

新增：

```text
GET /api/v1/knowledge/documents/{documentId}/pages
```

返回结构示意：

```json
{
  "code": 200,
  "data": {
    "documentId": "32位十六进制ID",
    "source": "来源系统",
    "title": "文档标题",
    "version": "文档版本",
    "contentType": "application/pdf",
    "status": "COMPLETED",
    "pageCount": 11,
    "updatedAt": "2026-11-09T18:19:53.633781",
    "pages": [
      {
        "pageNumber": 1,
        "content": "第1页提取文本",
        "characterCount": 1518,
        "blankPage": false,
        "parserName": "Apache PDFBox",
        "parserVersion": "3.0.7",
        "updatedAt": "2026-11-09T18:19:53.633781"
      }
    ]
  }
}
```

只有 `COMPLETED` 文档允许读取页面结果，其余状态返回 `4091`。页面正文接口从通用访问日志排除，避免整页原文进入响应日志。

## 11. 页码定位策略

- D27 使用 PDF 物理页面序号，固定从 1 开始。
- 不读取或推断印刷页码。
- PDF 阅读器显示页码、印刷页码和物理页序号可能不同。
- 多页 PDF 每页独立一行，不保存“整份 PDF 一个 pageNumber”。
- 空白页不丢定位信息。

## 12. 状态、事务与异常处理

状态保持：

```text
PENDING -> PROCESSING -> COMPLETED
                      -> FAILED
```

事务边界：

1. 抢占 `PROCESSING` 和替换旧页面结果在短事务内完成。
2. PDF 解析在数据库事务外执行。
3. 文档 `COMPLETED` 和全部页面插入在同一个完成事务中提交。
4. 任一页面插入失败会回滚整个完成事务。
5. 解析异常写入 `FAILED`，页面结果不会伪装为成功。

稳定错误码：

| 业务码 | 场景 |
|---:|---|
| `4101` | 格式伪装、扩展名不支持、文件读取或编码不合法 |
| `4221` | PDF 损坏、零页、全部空白、解析异常 |
| `4091` | 非完成状态读取页面、状态冲突 |
| `4092` | 相同内容已由其他文档导入 |

## 13. Word 可选扩展

已实现 `.docx` 整文纯文本提取：

- 复用 Tika 3.3.1 与传递的 POI 5.5.1。
- 提取结果保存到兼容的 `parsed_text`。
- 不为 Word 生成 PDF 物理页码，页面接口返回空列表。
- 不还原 Word 的分页、页眉页脚、表格结构或浮动对象。
- 不支持旧版 `.doc`、宏文档安全执行或 OCR。

## 14. 关键代码文件

| 文件 | 职责 |
|---|---|
| `KnowledgeDocumentParser.java` | PDF/Word/TXT/HTML 识别与页面提取 |
| `ExtractedPage.java` | 解析器输出的单页对象 |
| `KnowledgeDocumentPage.java` | 已持久化页面结果 |
| `KnowledgeDocumentPagePO.java` | MyBatis-Plus 页面行 |
| `KnowledgeDocumentPageMapper.java` | 页面表访问 |
| `KnowledgeDocumentController.java` | 登记、导入、详情和页面查询 |
| `KnowledgeDocumentService.java` | 事务外解析和完成事务编排 |
| `KnowledgeDocumentRepository.java` | 元数据、原始文件、页面结果存储端口 |
| `MybatisKnowledgeDocumentRepository.java` | MySQL 事务实现 |
| `schema-knowledge-document.sql` | 元数据、原始文件、页面结果三表迁移 |

## 15. 真实 PDF 原文与提取文本抽查

使用本地真实样本：

```text
E:\AI\project\AI-Learning-one\M3-Java\docs\proj_docs\D27-佳能用户手册-DB-测试素材.pdf
```

关键结果：

| 项目 | 结果 |
|---|---|
| 文件大小 | 1,509,800 字节 |
| SHA-256 | `566a1f050d7cf512c750d1c0f5e699438d839dca7b0cf1266ef282c8dfbee83a` |
| 总页数 | 11 |
| 解析器 | Apache PDFBox 3.0.7 |
| 提取总字符数 | 9,289 |
| 页面序号 | 1 到 11 连续且顺序正确 |

抽查：

- 第 1 页：标题、Introduction、Safety Information 提取正确。
- 第 2 页：Figure 3.1、Setup、Unpacking 提取正确。
- 第 10 页：故障表文本、Specifications 和 Warranty 正文存在。
- 第 11 页：Privacy details、提交按钮表单文字和链接文本存在。
- 第 1、2、10、11 页已渲染 PNG 并目视核对。

完整逐页文本：`docs/evidence/D27/D27-java-pdf-extraction.json`。
原文对照记录：`docs/evidence/D27/D27-real-pdf-quality-check.md`。

## 16. 自动化测试结果

基线：

```text
Tests run: 208, Failures: 0, Errors: 0, Skipped: 1
```

最终全量：

```text
Tests run: 217, Failures: 0, Errors: 0, Skipped: 1
```

新增测试覆盖：

- 正常 PDF 页面提取。
- 多页文本边界和 1 起始物理页码。
- 中间空白页保留。
- 零页 PDF 和全空白 PDF 失败。
- 损坏 PDF 失败。
- 图片型扫描 PDF 明确提示 OCR 边界。
- 中文、英文和数字混合 PDF。
- `.docx` 整文提取。
- 数据库页面行与 documentId 关联。
- 来源、标题、版本和解析器信息返回。
- 未完成文档拒绝读取页面。
- 真实 11 页 Canon PDF 导入和页面查询。

唯一跳过项为默认禁用的真实 DeepSeek 烟雾测试，本轮没有调用真实模型。

## 17. Maven 编译与日志

| 阶段 | 结果 | 日志 |
|---|---|---|
| 依赖解析 | 成功，PDFBox 3.0.7 | `logs/D27/D27-dependency-tree.log` |
| 主代码编译 | 成功 | `logs/D27/D27-compile-02.log` |
| 测试编译 | 成功 | `logs/D27/D27-test-compile-01.log` |
| 解析器专项 | 7/7 通过 | `logs/D27/D27-parser-tests-02.log` |
| 文档集成专项 | 12/12 通过 | `logs/D27/D27-document-integration-01.log` |
| 真实 PDF 证据 | 1/1 通过 | `logs/D27/D27-real-pdf-evidence-01.log` |
| 最终全量 | 217 项通过，1 跳过 | `logs/D27/D27-full-regression-01.log` |

## 18. 当前边界与后续工作

尚未实现：

- OCR 和扫描件文字识别。
- 表格单元格、跨行跨列和边框结构恢复。
- 多栏阅读顺序、段落语义和标题层级重建。
- Word 分页定位和版式还原。
- Chunk 智能切分、Embedding、VectorStore 和 RAG 检索问答。
- PDF 进程崩溃后的 `PROCESSING` 自动恢复扫描。

页面文本是后续 RAG Chunk 的输入，但页面记录不等同于 Chunk。后续切分必须继续保留 `documentId + physicalPageNumber`，以保证检索答案可以回到原始 PDF 页面。
