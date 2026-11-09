# D27 PDF 解析与来源元数据技术原理及操作实践指南

## 1. PDF 文档的基本结构

PDF 不是按“行”或“段落”直接存储的文本文件。一个常见 PDF 包含：

- 文件头与版本，例如 `%PDF-1.4`。
- 间接对象，包括页面、字体、图片、内容流和元数据。
- 页面树，通过 `/Pages` 和 `/Kids` 组织页面顺序。
- 交叉引用表和 trailer，用于定位对象。
- 每个页面的内容流，用绘图和文本定位指令描述视觉结果。
- 字体资源与字符编码映射。
- XMP/Info 文档元数据。

因此“PDF 中能看到文字”不等于“PDF 中存在按自然段保存的字符串”。文本提取器需要解释页面内容流、字体编码和字符映射，再根据版面位置组织输出。

## 2. PDF 文本提取的基本原理

PDFBox 处理普通文本 PDF 的基本过程是：

1. 解析 PDF 对象和页面树。
2. 按页面顺序找到页面内容流。
3. 解释文本显示指令。
4. 根据字体编码把字形映射为 Unicode。
5. 根据文本位置、换行和间距组织字符。
6. 输出每个页面的文本。

它依赖 PDF 中存在可解码字符映射。扫描件通常只有页面图片像素，没有对应文字对象，因此文本提取器不能替代 OCR。

## 3. Apache PDFBox 3.0.7 的技术特点

本项目使用 PDFBox 3.0.7，原因和特点如下：

- 是 Spring AI 1.1.8 PDF Reader 的实际传递版本。
- 支持 PDF 页面树、字体、图片、内容流和文本抽取。
- `Loader.loadPDF(byte[])` 适合本项目已经通过 Multipart 读入内存的文件。
- `PDFTextStripper` 可以指定起始页和结束页。
- 可直接用 `Version.getVersion()` 记录实际解析器版本。
- 需要自行处理版面重建、表格和 OCR，不能把纯文本成功等同于结构完整。

本项目没有引入 iText、PDFBox 1.x 或第二套 PDF 引擎，避免重复依赖和 API 版本错配。

官方参考：

- Apache PDFBox 3.0.7 Javadocs：https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.7/
- Apache PDFBox 项目：https://pdfbox.apache.org/
- Apache PDFBox 3.x 迁移说明：https://pdfbox.apache.org/3.0.0/migration.html
- Spring AI 1.1 Reference：https://docs.spring.io/spring-ai/reference/1.1/

## 4. PDF 物理页码与印刷页码

物理页码表示页面在 PDF 页面树中的顺序：

```text
index 0 -> physical page 1
index 1 -> physical page 2
```

印刷页码是页面视觉内容中可能印出的“第 3 页”“Page iv”等字符串。二者可能不同：

- 封面可能没有印刷页码。
- 前言可能使用罗马数字。
- 扫描拼接后印刷页码可能跳号。
- PDF 阅读器可能基于标签或偏移显示另一套页码。

D27 只保存物理页码，要求从 1 开始。页面接口字段明确命名为 `pageNumber`，当前语义是物理页序号，不把识别到的页脚数字当作定位键。

## 5. 页面级文本提取与来源定位

项目为每一页单独执行一次 `PDFTextStripper`：

```java
PDFTextStripper stripper = new PDFTextStripper();
stripper.setStartPage(pageNumber);
stripper.setEndPage(pageNumber);
String text = stripper.getText(document);
```

如果整份 PDF 一次性提取，句尾和下一页标题可能直接拼接，无法可靠恢复边界。逐页设置起止页后，每个 `ExtractedPage` 都包含：

```text
physicalPageNumber + normalizedPageText + characterCount + blankPage
```

页面行最终通过 `document_id` 回到 `knowledge_document`：

```text
knowledge_document
  document_id
  source_system
  title
  version
  status
        |
        | 1:N
        v
knowledge_document_page
  document_id + page_number
  content
  parser_name/version
```

## 6. PDF 文档元数据

PDF 元数据可能包含标题、作者、创建日期、生成器和 XMP 信息。它是文档描述信息，不是正文证据。

真实样本的 Info 元数据：

```text
Title:   Canon PIXMA MP500 All-In-One Photo Printer, Copier, and Scanner User Manual
Creator: wkhtmltopdf 0.12.6
Producer: Qt 4.8.7
CreationDate: D:20261024080126Z
```

本项目业务标题和版本使用登记时写入的 `title/version`，PDF 内部元数据只作为样本识别辅助，不自动覆盖人工登记的业务元数据。

## 7. documentId 与解析结果的关联

关联键只用服务端生成的 `documentId`：

```text
knowledge_document.document_id
    = knowledge_document_content.document_id
    = knowledge_document_page.document_id
```

设计原则：

- 同一文档的原始文件、页面文本和状态共享同一个 ID。
- 不通过标题、文件名或正文相似度猜测关联。
- 同一文档禁止替换成不同内容；内容变化应形成新版本/新记录。
- 页面唯一键包含 `document_id + page_number`，避免重复页。
- 查询时显式按页码升序，不依赖数据库默认行顺序。

## 8. PDF 文本提取顺序问题

阅读顺序不等于 PDF 对象存储顺序。内容流可能先绘制背景、再绘制侧栏、最后绘制正文；没有标签或结构信息的 PDF 更容易出现顺序偏差。

D27 使用 PDFBox 默认 `PDFTextStripper`：

- 对单栏、从上到下、从左到右的普通文档效果较好。
- 能保留基本行和段落间距。
- 不保证复杂多栏、浮动文本框和绝对定位元素符合人类阅读顺序。
- 不应根据 PDF 对象顺序推导业务章节。

真实样本抽查中，首页标题、Introduction、Safety 和 Package Contents 的提取顺序与视觉阅读顺序基本一致。第 10 页表格先提取单元格文字，规格列表随后出现。

## 9. 多栏排版与复杂布局的局限

默认文本抽取不能可靠恢复：

- 多栏之间应先读哪一栏。
- 浮动说明框与主正文的从属关系。
- 页眉页脚是否应排除。
- 图形标签和正文的语义关系。
- 绝对定位标题的层级。

这些场景需要版面分析、坐标聚类、阅读顺序模型或结构化 PDF 标签支持。D27 的目标是保存可追溯页面文本，不把默认顺序当作完整版面理解。

## 10. 表格提取的技术边界

PDF 表格通常只是线条和文本坐标的组合，不是数据库式行列对象。PDFBox 纯文本结果可能表现为：

```text
Problem
Solution
Cannot scan from computer.
Ensure the USB cable is securely connected.
```

人能理解单元格对应关系，但字符串本身不含结构化行列表信息。D27 因此：

- 保留表格中的可提取文字。
- 不声称表格结构完整。
- 不自动生成工单字段或数据库记录。
- 后续若需要表格结构，应增加坐标、规则或表格专用解析，而不是从当前换行文本猜测。

## 11. 扫描型 PDF 与 OCR 的区别

扫描 PDF 的页面可能只有一张位图。PDFBox 可以读取页面和图片，但默认文本抽取没有可解码文字。

D27 的规则：

- 全部页面无有效文本时返回 `DOCUMENT_PARSE_FAILED(4221)`。
- 错误提示明确说明扫描型 PDF 需要 OCR。
- 不把空字符串写入成功页面。
- 不伪造“解析成功”。

本项目测试使用内存绘制的图片型 PDF，提取不到文字并按上述规则失败。OCR、版面识别和图片文字抽取属于后续能力。

## 12. 中文及特殊字符提取问题

中文能否正确提取取决于：

- PDF 字体是否包含 ToUnicode 映射。
- 字体编码是否标准。
- 字符是否被转换为字形但没有 Unicode 映射。
- 文档是否加密或受复制限制。
- 特殊符号、项目符号和连字是否在字体中有可靠映射。

PDFBox 3.x 对嵌入字体的常规文本 PDF 有良好支持。项目专项测试创建了包含“设备故障码 E05”和“Replace Print Head 123”的中英混合 PDF，验证基础中文、英文和数字均能提取。

仍需注意：

- 中文字符数使用 Java `String.length()`，不是严格 Unicode code point 数。
- 带组合字符或扩展平面字符时，字符计数可能大于视觉字符数。
- 扫描图片中的中文只能通过 OCR 识别。

## 13. 损坏 PDF 与解析异常处理

PDF 解析异常可能来自：

- 文件头或交叉引用损坏。
- 页面树缺失。
- 内容流截断。
- 字体对象损坏。
- 加密但未提供密码。
- 恶意压缩对象或异常资源。

处理链路：

```java
try (PDDocument document = Loader.loadPDF(file.content())) {
    // page-by-page extraction
} catch (KnowledgeDocumentException ex) {
    throw ex;
} catch (Exception ex) {
    throw new KnowledgeDocumentException(
            ResultCode.DOCUMENT_PARSE_FAILED,
            "PDF 解析失败",
            ex);
}
```

服务层捕获异常后，将文档状态写为 `FAILED`，保存稳定错误码和清洗后的错误消息。底层堆栈不直接返回给客户端。

## 14. 解析结果的存储与追溯

PDF 原始字节保存在 `knowledge_document_content.content`。页面提取文本保存在 `knowledge_document_page.content`，二者分表保存。

建议迁移命令：

```powershell
mysql --default-character-set=utf8mb4 -u <user> -p ai_db `
  --execute="source E:/AI/project/AI-Learning-two-lp/M3-Java-ds/D27/D27_PDF_WORD/src/main/resources/db/schema-knowledge-document.sql"
```

追溯路径：

```text
documentId
  -> knowledge_document: source/title/version/status
  -> knowledge_document_content: original bytes
  -> knowledge_document_page: physical page + extracted text
```

页面查询不允许非 `COMPLETED` 文档返回文本。原始页面正文访问接口被排除在通用访问日志外，减少正文二次落盘风险。

## 15. PDF 与 Word 解析的技术差异

| 维度 | PDF | DOCX |
|---|---|---|
| 基本结构 | 页面树、内容流、字体对象 | ZIP + OOXML 文档 XML |
| 文本来源 | 页面内容流和字体映射 | 段落、表格、运行节点 |
| 固定分页 | 通常有明确页面对象 | 通常由渲染器动态分页 |
| 物理页定位 | 可直接使用页面树序号 | 需要渲染引擎后才能稳定确定 |
| 表格 | 线条/坐标，非天然单元格结构 | 有 OOXML 表格结构 |
| 文本顺序 | 依赖版面与标签 | 段落 XML 顺序更明确 |

D27 对 `.docx` 使用 Tika 整文提取，因 Word 文件本身没有与 PDF 页面树等价的固定物理页结构，不能在没有渲染的情况下伪造页码。

Apache Tika 3.3.1 参考：https://tika.apache.org/3.3.1/

## 16. 原文与提取文本质量验证方法

推荐组合以下方法：

1. 用 `pdfinfo` 记录页数、尺寸、元数据和加密状态。
2. 用 PDF 阅读器渲染关键页面为 PNG，人工观察标题、正文、列表、表格和空白。
3. 用 Java PDFBox 真实解析并保存逐页全文。
4. 断言页面数、物理页码序列和关键短语。
5. 对照每页字符数和预览，检查某页异常过短或过长。
6. 对表格检查文字是否存在，但不要宣称结构已恢复。
7. 对扫描件检查是否无文本，并记录 OCR 缺口。

D27 的 Java 证据：

```text
docs/evidence/D27/D27-java-pdf-extraction.json
```

目视渲染：

```text
docs/temp_docs/D27/render/page-01.png
docs/temp_docs/D27/render/page-02.png
docs/temp_docs/D27/render/page-10.png
docs/temp_docs/D27/render/page-11.png
```

## 17. D27 关键代码实践

### 文件识别

```java
detectedType = normalizeMime(new Tika().detect(file.content(), file.fileName()));
requireMime(file.extension(), detectedType, Set.of("application/pdf"));
```

### 页面对象

```java
public record ExtractedPage(int pageNumber, String content, boolean blankPage) {
    public int characterCount() {
        return content.length();
    }
}
```

### 页面事务

```java
documentMapper.update(... status = COMPLETED ...);
contentMapper.update(... parsedText for non-PDF only ...);
for (ExtractedPage page : pages) {
    pageMapper.insert(toPagePO(documentId, page));
}
```

### 页面读取

```java
return pageMapper.selectList(
        new LambdaQueryWrapper<KnowledgeDocumentPagePO>()
                .eq(KnowledgeDocumentPagePO::getDocumentId, documentId)
                .orderByAsc(KnowledgeDocumentPagePO::getPageNumber))
        .stream()
        .map(this::toPageDomain)
        .toList();
```

## 18. 常见故障及排查方法

### 导入返回 4101

检查：

- 扩展名是否在白名单。
- 文件是否零字节或超限。
- `.pdf` 是否实际被 Tika 识别为其他格式。
- 文本文件是否为合法 UTF-8。

### 导入返回 4221

检查：

- PDF 是否损坏或加密。
- 是否零页。
- 是否全部页面只有图片而没有文本。
- 是否超过最大页数或字符数。

### 页数为 0

可能原因：

- 文档是 Word/TXT/HTML，本来没有 PDF 页面记录。
- 文档尚未完成解析。
- 旧版本数据在 D27 前完成，数据库没有页面回填。

### 页面顺序异常

- 页面接口按 `page_number` 排序，应检查数据库值是否被外部脚本改写。
- PDFBox 页面树顺序代表物理页顺序；PDF 阅读器可能显示另一套标签页码。

### 中文乱码

- 检查 PDF 是否嵌入字体并有 ToUnicode。
- 检查是否加密限制文本复制。
- 检查是否实际为扫描图片。
- 不要用错误编码对 PDF 字节做 String 解码。

### 表格没有单元格

这是预期边界。当前只保存页面文本，不恢复表格结构。

### 图片文字没有提取

默认 PDFBox 不执行 OCR。需要增加独立 OCR 流程。

## 19. 后续 RAG 文档处理流程的衔接

推荐流程：

```text
KnowledgeDocument
  -> raw file
  -> PDF/Word parser
  -> page-level extraction
  -> reviewed chunk generation
  -> embedding
  -> vector store
  -> permission-aware retrieval
  -> answer with documentId + pageNumber citation
```

页面与 Chunk 的边界：

- 页面是原始文档的物理定位单位。
- Chunk 是面向检索和上下文窗口的语义切分单位。
- 一个页面可产生多个 Chunk。
- 每个 Chunk 必须保留 `documentId + physicalPageNumber`。
- 页面文本不能直接视为已审核、已发布或可检索内容。
- 后续权限过滤和版本失效必须在检索前执行。

本阶段只建立页面级基础，不提前实现 Embedding、VectorStore 或 RAG。
