# D28 文本清洗与 Chunk 切分功能实现全量说明

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 迭代名称 | Day 28：文本清洗与切分策略 |
| 项目根目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D28` |
| 模块 | `D28_TCASS` |
| 完成日期 | 2026-11-11 |
| 核心目标 | 建立可配置、可复用、来源可追溯的文本清洗与 Chunk 切分组件 |

## 2. 目标与需求背景

D28 已继承 D27 的 PDFBox 逐页解析能力，页面表可以保存 documentId、页码和页面文本。本轮在此基础上解决以下问题：

- PDF 提取文本中的 CRLF、连续空格、异常空白和重复空行需要规范化。
- 切分参数不能散落在业务代码中。
- 固定长度切分和结构感知切分需要独立执行并对比。
- 每个 Chunk 必须保留文档身份、版本、页码、段落和 Chunk 索引。
- 跨页或跨段落的 Chunk 不能只保留第一个来源。
- 极短段落不能丢失，超长段落和超长单句不能无限增长。

本轮不调用模型、不做 Embedding、不连接 VectorStore，也不修改正式检索链路。

## 3. 现有文档解析结果

复用 D27 页面结果：

```text
knowledge_document_page
  document_id
  page_number
  content
  character_count
  blank_page
  parser_name
  parser_version
  updated_at
```

同时读取：

```text
knowledge_document
  document_id
  source_system
  title
  version
  status
```

只有 `COMPLETED` 且具有页面级文本的 PDF 文档才能进入本轮的 Chunk 预览。

## 4. 组件设计

新增独立包：

```text
com.zl.d28.knowledge.chunking
```

组件职责：

| 组件 | 职责 |
|---|---|
| `TextCleaner` | 保留页面关系的文本规范化和段落识别 |
| `DocumentChunker` | 固定长度与结构感知策略 |
| `KnowledgeDocumentChunkingService` | 将数据库页面结果适配为切分输入 |
| `KnowledgeDocumentChunkingController` | 只读 Chunk 预览接口 |
| `ChunkingProperties` | 外部化参数及校验 |

切分核心不依赖数据库、模型客户端、Embedding 或 VectorStore。

## 5. 文本清洗实现

### 5.1 处理规则

- `CRLF`、`CR`、Unicode 行分隔符统一为 LF。
- NBSP、全角空格和其他 `SPACE_SEPARATOR` 统一为普通空格。
- 清除 BOM、零宽空格和 word-joiner。
- 保留有语义的零宽连接符 `U+200D`，避免破坏组合 emoji。
- 移除无意义控制字符。
- 删除行尾空白。
- 连续水平空格压缩为一个空格。
- 连续空白行压缩为一个段落分隔空行。
- 保留标题、列表符号、标点、数字、单位、英文和中文。

### 5.2 原文与清洗文本关联

`CleanedPage` 同时保存：

```text
pageNumber
originalText
cleanedText
originalCharacterCount
cleanedCharacterCount
paragraphCount
```

因此清洗不会把来源页和原始文本丢掉。

### 5.3 段落识别

保守启发式：

- Markdown 标题识别。
- 多层数字标题，如 `5.3. Scanning Documents and Photos`。
- 短数字标题及全大写短标题。
- bullet、`*`、`-`、数字列表独立成段。
- 列表折行续写仍归属同一个列表项。
- 标题与后续正文使用不同段落索引，但切分器默认不在标题后立即断开。

该规则适合当前 PDF 抽取，但不声称完整理解复杂版面。

## 6. 长度单位

D28 统一使用 **Unicode code point 数量**：

- 不使用 Java UTF-16 code unit 作为配置长度。
- 不宣称当前长度等于 Token 数。
- 不会把 emoji 代理对切成两个无效半字符。
- 真实 Token 仍由后续模型 Tokenizer 决定，可能与码点长度明显不同。

## 7. 可配置参数

配置前缀：

```yaml
app:
  knowledge:
    chunking:
      default-strategy: FIXED_LENGTH
      chunk-size: 480
      overlap: 60
      min-chunk-size: 80
      max-chunk-size: 720
```

| 参数 | 默认值 | 规则 |
|---|---:|---|
| `default-strategy` | `FIXED_LENGTH` | `FIXED_LENGTH` 或 `STRUCTURE_AWARE` |
| `chunk-size` | 480 | 固定策略上限；结构策略目标 |
| `overlap` | 60 | `0 <= overlap < min-chunk-size` |
| `min-chunk-size` | 80 | 中间 Chunk 最小目标 |
| `max-chunk-size` | 720 | 结构策略硬上限，且不小于 chunk-size |

环境变量：

```text
KNOWLEDGE_CHUNK_STRATEGY
KNOWLEDGE_CHUNK_SIZE
KNOWLEDGE_CHUNK_OVERLAP
KNOWLEDGE_CHUNK_MIN_SIZE
KNOWLEDGE_CHUNK_MAX_SIZE
```

`ChunkingProperties.requireValid()` 在算法入口再次校验，直接构造组件时也不能绕过。

## 8. 固定长度切分

实现步骤：

1. 以 `chunk-size` 建立目标窗口。
2. 在最小长度之后优先回退到句末标点。
3. 无句末标点时回退到空白。
4. 无自然边界则按硬上限截断。
5. 下一窗口起点为 `end - overlap`。
6. 最后长度不足最小值的片段仍保留。
7. 空窗口跳过，不生成空 Chunk。

固定策略不会丢弃文本。真实样本中 25 个 Chunk 覆盖完整清洗文本，Overlap 精确为 60 码点。

## 9. 结构感知切分

实现步骤：

1. 在 `chunk-size` 目标与 `max-chunk-size` 硬上限之间查找段落结束位置。
2. 优先选择最靠后的安全段落边界。
3. 标题段落不作为立即结束点，避免标题与后文分离。
4. 没有安全段落边界时使用句末边界。
5. 超长单句没有句末边界时按最大长度硬切。
6. 下一窗口仍使用准确 Overlap。

真实样本中：

- 17 个 Chunk。
- 16 个结束在段落边界。
- 1 个结束在文档末尾。
- 结构语义边界覆盖率为 17/17。

## 10. 极短段落与超长段落

极短段落：

- 不丢弃。
- 结构策略在一个窗口内合并多个短段。
- 最后极短片段完整保留。

超长段落：

- 优先寻找句末标点。
- 单句仍过长时受 `max-chunk-size` 限制硬切。
- 不产生无限长度 Chunk。

## 11. Chunk 元数据

```java
record DocumentChunk(
    String documentId,
    String title,
    String version,
    int chunkIndex,
    ChunkStrategy strategy,
    String content,
    int characterCount,
    List<Integer> pageNumbers,
    List<ChunkSource> sources,
    ChunkBoundaryType endBoundary
) {}
```

`ChunkSource`：

```java
record ChunkSource(Integer pageNumber, Integer paragraphIndex) {}
```

规则：

- `chunkIndex` 从 1 开始并连续递增。
- `pageNumbers` 去重且保持来源顺序。
- `sources` 保存所有覆盖的页面和段落。
- `paragraphIndex` 在每页内从 1 开始。
- 跨页 Chunk 可以包含多个页面来源。
- 同一 documentId、不同 version 的 Chunk 通过 version 区分。
- 不同文档即使 chunkIndex 相同，也通过 documentId 区分。
- 修改切分参数后应重新生成整份文档 Chunk；当前预览结果不持久化。

## 12. 页面与段落来源定位

跨页示例：

```json
{
  "pageNumbers": [1, 2],
  "sources": [
    {"pageNumber": 1, "paragraphIndex": 7},
    {"pageNumber": 2, "paragraphIndex": 1}
  ]
}
```

如果一个 Chunk 跨越多个页面，不会只保留第一页。若来源没有页码，字段允许为空，但当前 PDF 链路始终提供物理页码。

## 13. 只读预览接口

### 单策略

```text
GET /api/v1/knowledge/documents/{documentId}/chunks?strategy=STRUCTURE_AWARE
```

### 两种策略对比

```text
GET /api/v1/knowledge/documents/{documentId}/chunks/compare
```

接口只返回内存计算结果，不写正式业务表，不触发 Embedding。完整 Chunk 正文接口已从通用访问日志排除。

## 14. 关键代码文件

| 文件 | 职责 |
|---|---|
| `TextCleaner.java` | 字符规范化、段落、标题和列表识别 |
| `DocumentChunker.java` | 两种切分算法、Overlap、来源映射和指标 |
| `ChunkingProperties.java` | 外部化参数和校验 |
| `ChunkSourceDocument.java` | 输入文档身份和页面列表 |
| `CleanedDocument.java` | 清洗结果和段落偏移 |
| `DocumentChunk.java` | 可追溯 Chunk 契约 |
| `ChunkingResult.java` | 配置快照、统计和 Chunk 列表 |
| `KnowledgeDocumentChunkingService.java` | 数据库页面结果适配 |
| `KnowledgeDocumentChunkingController.java` | REST 预览入口 |

## 15. 真实 PDF 切分对比

同一输入：

| 项目 | 值 |
|---|---|
| 文件 | `D28-佳能用户手册-DB-测试素材.pdf` |
| SHA-256 | `566a1f050d7cf512c750d1c0f5e699438d839dca7b0cf1266ef282c8dfbee83a` |
| 页数 | 11 |
| 原始码点 | 9,289 |
| 清洗后码点 | 9,215 |
| 段落数 | 63 |

对比结果：

| 指标 | 固定长度 | 结构感知 |
|---|---:|---:|
| Chunk 数 | 25 | 17 |
| 平均长度 | 426.20 | 598.53 |
| 最小长度 | 135 | 309 |
| 最大长度 | 468 | 697 |
| 段落/文档末尾边界 | 1 | 17 |
| 句末边界 | 24 | 0 |
| 硬截断 | 0 | 0 |

结论：

- 固定长度 Chunk 更短、数量更多，适合无可靠结构文本。
- 结构策略数量更少，全部结束在段落或文档末尾，适合有标题、列表和段落的手册。
- 固定策略最大长度仍小于 480，结构策略最大长度小于 720。
- 两种策略均保留 60 码点 Overlap、完整来源和连续 chunkIndex。

详细数据：`docs/evidence/D28/D28-chunking-comparison.json`。

## 16. 自动化测试

覆盖：

- 空文本、纯空白。
- CRLF、NBSP、BOM、连续空格、重复空行。
- 标题、列表、中文、英文、数字、单位和技术符号。
- 组合 emoji 的 ZWJ 保留。
- Overlap 为 0。
- Overlap 大于 0 且相邻片段前缀等于前一片段尾部。
- Overlap 非法配置。
- 极短最后一段。
- 多个连续短段落合并。
- 超长段落和超长单句。
- 中文和 emoji 代理对。
- 跨页、跨段落来源。
- 同索引不同文档版本。
- 真实 11 页 PDF 双策略对比。
- 未完成文档拒绝 Chunk 预览。

最终全量结果为 **235 项测试，0 失败，0 错误，1 跳过**。唯一跳过项为既有真实 DeepSeek 烟雾测试。

## 17. Maven 编译

实际执行：

```text
mvn -f .\pom.xml -DskipTests compile
mvn -f .\pom.xml test
mvn -f .\pom.xml -DskipTests package
```

编译、全量测试和打包日志保存在 `logs/D28`。

## 18. 当前边界与后续工作

已实现：

- 文本清洗。
- Unicode 码点长度。
- 固定长度与结构感知切分。
- Chunk Size、Overlap、最小/最大长度。
- 页级、段级来源和 Chunk 索引。
- 可重复的算法指标和真实 PDF 对比。

未实现：

- Tokenizer 精确 Token 长度。
- 持久化 Chunk generation 和发布状态。
- Embedding、VectorStore、向量召回和 RAG。
- 复杂多栏、表格结构、OCR。
- 使用大模型进行语义质量评分。

当前 Chunk 是预览数据，不是已发布检索数据。后续接入 Embedding 时，必须先固定文档版本、清洗器和切分配置，并为每次生成建立明确 generation 标识。
