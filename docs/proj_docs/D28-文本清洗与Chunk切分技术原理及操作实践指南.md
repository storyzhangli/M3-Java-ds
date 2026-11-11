# D28 文本清洗与 Chunk 切分技术原理及操作实践指南

## 1. 为什么 RAG 需要文本清洗和切分

PDF 文本提取得到的是视觉排版解释结果，不是已经整理好的知识段落。常见噪声包括：

- 换行符混用。
- PDF 折行导致句子被切成多行。
- 标题、列表、表格内容被抽成连续文本。
- 页眉页脚和表单文字混入正文。
- NBSP、全角空格、BOM 和零宽字符。
- 多页文本直接拼接。

RAG 若直接对这些原始字符串按固定长度截断，容易出现：

- 标题和正文分离。
- 句子被从中间切断。
- 引用只能定位到整份文档，不能定位到页面或段落。
- 超短 Chunk 缺少语义，超长 Chunk 超出模型上下文。
- 跨页内容只保留第一页来源。

清洗解决“文本表示噪声”，切分解决“检索粒度和上下文预算”，来源元数据解决“答案证据追溯”。三者必须同时设计。

## 2. 原始文本清洗的基本原理

清洗不是把文本变成统一一行，也不是尽可能删除字符。正确原则是：

1. 规范表示，不改变有效内容。
2. 合并无意义空白，保留标题、段落和列表边界。
3. 只删除可证明无意义的控制字符。
4. 原文与清洗文本分开保存。
5. 每页单独清洗，避免跨页边界先被破坏。

D28 的清洗顺序：

```text
原始页文本
 -> 统一换行
 -> 规范 Unicode 空白
 -> 移除 BOM/零宽噪声
 -> 保留 ZWJ 等语义字符
 -> 行级空白压缩
 -> 空白行折叠
 -> 标题/列表/段落切段
```

### 空白处理

- 连续水平空格压缩为一个空格。
- 段落之间的多个空行压缩为一个空行。
- 行尾空白删除。
- 换行本身保留。

这样不会破坏技术文本中的数字、单位、标点或术语。

### 零宽字符

- `U+FEFF` BOM：删除。
- `U+200B` 零宽空格：删除。
- `U+2060` word-joiner：删除。
- `U+200D` zero-width joiner：保留，因为组合 emoji、部分书写系统可能依赖它。

“零宽”不等于“无语义”，不能一概删除。

## 3. 固定长度切分

固定长度切分的逻辑：

```text
start = 0
while start < length:
    targetEnd = min(start + chunkSize, length)
    end = chooseNaturalBoundary(start, minimum, targetEnd)
    emit(text[start:end])
    start = end - overlap
```

Chunk Size 是局部窗口，Overlap 是相邻窗口共享的尾部。

### 为什么还要寻找自然边界

纯按位置切会得到：

```text
... connect the USB
cable to your computer ...
```

虽然长度准确，但 Chunk 更容易在句子中间结束。D28 在目标窗口内优先向回寻找：

1. 句号、问号、感叹号、分号，包括中英文。
2. 空白。
3. 不存在自然边界时硬切。

这个回退不能超出 `min-chunk-size`，否则会得到大量极短 Chunk。

## 4. 按标题切分

标题是后续检索的高价值语义锚点。D28 识别：

- Markdown 标题 `#`。
- 多层数字标题，如 `5.3. Scanning Documents`。
- 短数字标题。
- 短全大写标题。

标题并不直接作为一个独立 Chunk。切分器记录标题边界，但结构策略不会在标题后立即结束，因为“标题 + 紧随正文”通常比只有标题更有语义。

### 标题识别边界

PDF 不保留可靠的 Heading 样式时，只能使用启发式。以下情况可能误判：

- 全大写产品名。
- 以数字开头的列表步骤。
- 表格中的短单元格。

因此 D28 把标题识别视为辅助信息，不把它当作强 Schema。复杂文档需要版面分析或人工审核。

## 5. 按段落切分

清洗器保留标题、列表和普通正文之间的结构：

- 标题行开始新段落。
- 每个 bullet/编号列表项开始新段落。
- 列表折行续写追加到同一段。
- 空行强制断开段落。
- 普通连续行属于同一段，除非遇到标题或列表。

结构感知切分优先选择段落末尾：

```java
Integer paragraphEnd = lastBoundaryWithin(
        safeParagraphEnds, minimum, maximum);
```

如果是安全段落边界，当前 Chunk 结束类型为 `PARAGRAPH`。

## 6. Chunk Size 的含义及选择

Chunk Size 是检索单元的最大目标长度，不是文档最大长度，也不是 Prompt 总预算。

过小：

- 语义碎片化。
- 标题与正文分离。
- 检索需要更多片段才能回答完整问题。
- 元数据和索引成本上升。

过大：

- 一个 Chunk 混入多个主题。
- 相似度被无关文本稀释。
- 上下文预算容易超出。

D28 默认：

```text
chunk-size = 480 Unicode code points
max-chunk-size = 720 Unicode code points
```

真实 Canon PDF 的结果：

- 固定策略平均 426.20 码点。
- 结构策略平均 598.53 码点。

结构策略允许在目标值附近延伸到完整段落，但不超过 720。

## 7. Overlap 的含义及作用

Overlap 让相邻 Chunk 共享尾部/头部：

```text
Chunk N:     [..............]
Chunk N+1:             [..............]
```

作用：

- 降低边界附近语义被切掉的概率。
- 让句子上下文或短结论在相邻片段中可见。

代价：

- 总文本量增加。
- Embedding 和存储成本增加。
- 可能检索到重复内容。

D28 的 Overlap 是准确码点数量：

```text
nextStart = currentEnd - overlap
```

当前测试断言下一 Chunk 的前缀等于上一 Chunk 的最后 60 码点。

配置要求：

```text
0 <= overlap < min-chunk-size <= chunk-size <= max-chunk-size
```

如果 Overlap 大于等于最小 Chunk，窗口可能无法有效前进，因此拒绝该配置。

## 8. 字符数与 Token 数的区别

Token 不是字符。不同模型 Tokenizer 的切词方式不同：

- 一个英文单词可能是一个或多个 Token。
- 一个中文字可能是一个或多字节级 Token。
- emoji 可能占多个 Token。
- JSON、代码和特殊符号通常 Token 密度不同。

D28 明确使用 Unicode code point：

```java
text.codePointCount(0, text.length())
```

不把 `chunkSize=480` 声称为 480 Token。没有可靠 Tokenizer 时宣称 Token 长度是误导。

## 9. Token 长度限制及工程处理

生产系统若必须控制 Token，应按以下流程：

1. 固定模型和 Tokenizer 版本。
2. 使用与模型一致的 Encode API。
3. 对 Chunk 文本计算真实 Token。
4. 将字符配置保留为快速估计与兜底。
5. 对超限 Chunk 再按 Token 边界切分。
6. 保存 Tokenizer 版本和 Token 数。

当前 D28 只实现码点策略，后续可在不改变来源元数据契约的前提下替换长度计算器。

## 10. 语义边界保护

优先级：

```text
段落边界 > 句末边界 > 空白边界 > 硬截断
```

真实样本对比：

| 策略 | Chunk 数 | 段落/文档末尾 | 句末 | 硬切 |
|---|---:|---:|---:|---:|
| 固定长度 | 25 | 1 | 24 | 0 |
| 结构感知 | 17 | 17 | 0 | 0 |

固定策略仍然保护句子，但没有足够的段落级完整性。结构策略以更多长度波动换取更完整的语义边界。

## 11. 极短段落处理

极短段落有两种情况：

- 文档中本来就有短标题、短列表项或短提示。
- 文档最后剩余极短文本。

D28 规则：

- 极短段落不删除。
- 结构策略可将连续短段合并。
- 最后不足 `min-chunk-size` 的片段仍保留。
- 不为了满足最小值而丢弃尾部文本。

测试覆盖最后仅 1 个码点的情况。

## 12. 超长段落处理

超长段落不能在 `max-chunk-size` 外无限增长。D28 先尝试句子边界：

```java
Integer sentenceEnd = findSentenceBoundary(text, minimum, maximum);
```

如果一句话本身超过最大长度，只能硬切：

```java
return new Boundary(maximum, ChunkBoundaryType.HARD_LIMIT);
```

这仍然保留来源元数据，但应在质量指标中暴露硬切，不能隐藏。

## 13. Chunk 元数据结构

```text
documentId
title
version
chunkIndex
strategy
content
characterCount
pageNumbers[]
sources[]: pageNumber + paragraphIndex
endBoundary
```

### chunkIndex

- 文档内从 1 开始。
- 与来源页面的物理页码无关。
- 同一配置下按文本顺序稳定生成。
- 修改 Chunk Size、Overlap、清洗规则或策略后应视为新 generation。

### endBoundary

| 类型 | 含义 |
|---|---|
| `PARAGRAPH` | 结束在段落边界 |
| `SENTENCE` | 结束在句末 |
| `WHITESPACE` | 结束在空白 |
| `HARD_LIMIT` | 达到硬长度 |
| `DOCUMENT_END` | 文档最后片段 |

## 14. 文档 ID、版本、页码与段落定位

文档身份：

```text
documentId + version
```

来源定位：

```text
documentId + version + pageNumber + paragraphIndex
```

Chunk 身份建议：

```text
documentId + version + generation + chunkIndex
```

当前预览结果尚未持久化 generation；正式持久化时必须增加该维度，避免同一版本不同配置产生索引冲突。

## 15. 跨页 Chunk 的来源追溯

跨页组合不能只保留第一页。示例：

```json
{
  "pageNumbers": [1, 2],
  "sources": [
    {"pageNumber": 1, "paragraphIndex": 7},
    {"pageNumber": 2, "paragraphIndex": 1}
  ]
}
```

实现：

```java
for (CleanedParagraph paragraph : paragraphs) {
    if (paragraph.startCodePoint() < end
            && paragraph.endCodePoint() > start) {
        sources.add(new ChunkSource(
                paragraph.pageNumber(),
                paragraph.paragraphIndex()));
    }
}
```

这样 Overlap 跨页时仍保留两个来源。

## 16. 不同切分策略的优缺点

| 策略 | 优点 | 局限 | 适用 |
|---|---|---|---|
| 固定长度 | 可预测、实现简单、长度稳定 | 段落完整性弱 | 结构混乱、无标题文本 |
| 结构感知 | 语义边界好、数量少 | 依赖标题/段落识别、长度变化 | 手册、制度、说明书 |

真实数据中：

- 固定策略长度 135 到 468。
- 结构策略长度 309 到 697。
- 结构策略以更大的长度波动获得完整段落。

不应预设某一种策略总是更好，应以文档类型和评测指标选择。

## 17. 切分效果评估方法

建议同时记录：

- 原始和清洗后码点数。
- Chunk 数。
- 平均、最小、最大长度。
- 长度分布。
- 边界类型分布。
- Overlap 是否准确。
- 空 Chunk。
- 文本是否覆盖完整且无意外缺口。
- 来源页面和段落是否完整。
- 人工抽查标题、句子和列表完整性。
- 后续检索命中率和答案引用准确率。

D28 真实对比：

| 指标 | 固定 | 结构 |
|---|---:|---:|
| Chunk 数 | 25 | 17 |
| 平均长度 | 426.2 | 598.5 |
| 最小长度 | 135 | 309 |
| 最大长度 | 468 | 697 |
| 语义边界 Chunk | 1 | 17 |

## 18. 常见切分质量问题及排查

### Chunk 以半个句子开始

- 检查 Overlap 和起始位置。
- 固定长度策略允许句中断开后续窗口，但结构策略应优先段落。
- 若标题识别错误，检查段落边界。

### Chunk 数量过多

- Chunk Size 是否太小。
- 标题或列表是否被错误识别成段落。
- Overlap 是否过大。

### 短 Chunk 过多

- `min-chunk-size` 是否合理。
- 结构策略是否没有合并短段。
- 最后文本是否被错误拆分。

### 超长 Chunk

- `max-chunk-size` 校验是否生效。
- 长句没有句末标点时是否使用硬切。
- 是否绕过配置直接调用了算法。

### 页码丢失

- 输入 `DocumentPageText.pageNumber` 是否为空。
- 从数据库读取页面时是否按页码排序。
- 跨页来源是否收集完整。

### Overlap 不精确

- 是否把 Token 和码点混用。
- 是否在起始位置额外 strip。
- 是否使用 Java String 索引直接截断代理对。

### 中文被截断或乱码

- 确认输入是有效 Unicode。
- 使用 `offsetByCodePoints` 和 `substring`。
- 检查 PDF 字体 ToUnicode 映射。

## 19. D28 关键代码操作实践

### 从 D28 页面结果生成预览

```text
GET /api/v1/knowledge/documents/{documentId}/chunks?strategy=FIXED_LENGTH
```

### 对比两种策略

```text
GET /api/v1/knowledge/documents/{documentId}/chunks/compare
```

### 直接调用组件

```java
ChunkingProperties properties = new ChunkingProperties();
properties.setChunkSize(480);
properties.setOverlap(60);
properties.setMinChunkSize(80);
properties.setMaxChunkSize(720);

DocumentChunker chunker =
        new DocumentChunker(new TextCleaner(), properties);
ChunkingComparison comparison = chunker.compare(sourceDocument);
```

### 清洗

```java
CleanedDocument cleaned = cleaner.clean(sourceDocument);
int codePoints = cleaned.normalizedText()
        .codePointCount(0, cleaned.normalizedText().length());
```

### 读取 Chunk 证据

```java
DocumentChunk chunk = result.chunks().get(0);
String documentId = chunk.documentId();
String version = chunk.version();
List<Integer> pages = chunk.pageNumbers();
List<ChunkSource> sources = chunk.sources();
```

## 20. 与后续 Embedding、VectorStore 的衔接

推荐流水线：

```text
Original file
  -> PDF/Word parser
  -> page extraction
  -> text cleaning
  -> chunk generation
  -> review
  -> embedding
  -> vector index
  -> permission-aware retrieval
  -> answer with documentId/page/chunk citation
```

持久化 Chunk 时应增加：

- `generationId`
- 清洗器版本
- 切分策略与参数快照
- 内容哈希
- 创建时间
- 发布/失效状态
- 权限和租户过滤字段

向量索引元数据至少保留：

```text
tenantId
documentId
version
generationId
chunkIndex
pageNumbers
permissionPolicyId/version
```

检索结果必须使用该元数据回到原始页面；不能只返回相似文本而不提供证据来源。

## 21. 参考资料

- Java 21 `String.codePointCount`：https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#codePointCount(int,int)
- Java 21 `String.offsetByCodePoints`：https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#offsetByCodePoints(int,int)
- Unicode Standard Annex #29，Text Segmentation：https://www.unicode.org/reports/tr29/
- Spring Boot Externalized Configuration：https://docs.spring.io/spring-boot/reference/features/external-config.html
- Apache PDFBox 3.0.7：https://pdfbox.apache.org/
- Apache Tika 3.3.1：https://tika.apache.org/3.3.1/
