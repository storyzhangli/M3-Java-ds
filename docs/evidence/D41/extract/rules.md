# D41 R01 解析规则说明与错误清单（extract/rules.md）

> 本文件是任务书 §6 交付物 #5 的规则明细，口径与 `probe\table-params.json` 冻结参数、`com.zl.d41.knowledge.table` 实际源码一致（源码为准，执行期修订见文末 D13 记录）。

## 1. 表格识别条件（PDF 主路径，PDFBox 3.0.7 坐标聚类）

1. 逐页收集 `TextPosition`（xDirAdj/yDirAdj/widthDirAdj/fontSize），物理页隔离，绝不跨页聚类。
2. **行聚类**：同页内按 yDirAdj 分组，容差 = 行字体大小 × 0.5（`rowClusterToleranceFactor=0.5`）。
3. **列切分**：行内按 xDirAdj 升序，相邻文本块间隙 > 24pt（`columnGapThresholdPt`）即断列。
4. **列锚**：对表格区域内多行的单元格起点 x 做并簇，多数行一致的起点即列锚。
5. **表格区域判定**：连续 ≥2 行、每行 ≥2 列单元格的文本块带才视为表格；纯段落文字不入库。
6. 文本来源为 PDFTextStripper **默认提取顺序**（非 sortByPosition），规范化后参与匹配（`textNormalization`：NFC + 全角空格→半角 + 连续空白折叠 + trim）。

## 2. 表头判定与表头下沉（headerRule / headerSinking）

- 区域首行 = 表头行；若首行 fontSize 中位数 ≥ 数据行 × 1.1，判强表头信号。
- 表头行**不产生数据记录**（下沉为 header_snapshot_json 列名数组，normalize 后存快照）。
- 每行数据记录以 `{"h":列名,"v":值,"t":类型,"u":单位}` 映射列锚，表头列名取自快照。

## 3. 表头行前置吸收（pendingRow，D13 修复新增）

- 场景：表格区域起始行之前出现的**单 cell 文本行**（PDFBox 将字间有间隙的宽排表头切成了多段或一段），可能是被切碎的表头。
- 规则：以**半间隙 12pt** 对该 pendingRow 重切；切得 ≥2 段 → 吸收为表头首行（例：`Col_A Col_B Col_C Col_D` 字距 ~17–22pt 的宽排表头）；连续中文标题半间隙重切仍为 1 段 → 不误吸（params/faultcode 中文标题回归安全）。

## 4. 合并单元格处置（mergedCellPolicy）

- 触发：单元格 x 覆盖宽度 > 所属列宽 × 1.5，且右邻列桶在该行无独立起点。
- 处置：值归**覆盖起点首列**，其余被覆盖列落 null；`detect_flags` 加 `MERGED_CELL`。
- 纪律：绝不静默错配到相邻列；对账时该格按首列有值、其余 null 断言。

## 5. 跨页续表（continuationRule / crossPageDataRows）

- 后续页首行 normalize 后与该文档前表 `header_snapshot` 做**规范化包含匹配**（pageNumber ∈ `vector_chunk_index.page_numbers` 且行文本 ⊆ 页文本，无全局偏移）。
- 命中 → 判续表：重复表头行**不入数据行**（A3 验收点）；续表生成新 table_index，`continuation_of_table_id` 指向前表（复用既有列，one 线 DDL 兼容）。
- 续表数据行独立成 table_extract 记录，page_number=实际页，逐页归属。

## 6. 类型化单元格（unitRegex / cellSchema）

- `type=number` 仅当数值段全匹配 `^[±+-]?\d+(\.\d+)?$`；单位段匹配 `[A-Za-zµ°%/][A-Za-z0-9µ°%/·.^-]*`，**数值与单位同记录不拆散**（A1）。
- 范围表达式（`2..8`）、复合串 → `type=string` 原文保留。
- **尺寸记号否决（D13 新增）**：单位段命中 `DIMENSION_SUFFIX ^[xX×].*\d`（如 `25x52x15`、`52x65x8`）→ 整体判 `string`、`unit=null`，防误判 number/unit="x52x15"。回归样例：`1500 rpm`=number/rpm、`380V`=number/V、`DN25`=string。
- 空单元格 → `v=null, t="null"` 占位，**不串相邻列**（A4）；全 null 行不产生数据记录（A4）。

## 7. 错误/退化码清单

| 码 | 触发 | 处置 | 样例证据 |
|---|---|---|---|
| DOCX_TABLE_NOT_STRUCTURED | .docx 走 Tika 纯文本，表格结构丢失 | 表域 status=DEGRADED + flags 标注，不猜结构 | exceptions\s3（A1b/A1c 域）、capability\kp2-* |
| IMAGE_TABLE_NOT_PARSED | 图片表格页（页文本 blank 且含 Image XObject），OCR 关/无引擎 | 主路径不崩溃，降级标注 IMAGE_TABLE_NOT_PARSED，表 status=DEGRADED | exceptions\s3（A5，T7-a5p2 9 项校验） |
| COLUMN_MISALIGNMENT | 区域列边界间距中位数 <60pt 或行单元格数≠区域列数 >1 行 | flags 标注，逐格 null 占位，不静默错配 | exceptions\s3（A2c/A4） |
| MERGED_CELL | §4 触发条件 | 值归首列其余 null + flags | exceptions\s3（A2b） |
| LOW_CONFIDENCE | 同 §7 COLUMN_MISALIGNMENT 半触发（间距规则） | flags 标注 | exceptions\s3（A2） |
| LINKAGE_MISSING | 行文本双轨包含匹配零命中 chunk | 关联字段标注，不伪造关联 | linkage\s4（missingCount=0 为正向） |
| LINKAGE_AMBIGUOUS | 命中 chunk 分属多 documentId | 文档级检索正常，仅跨文档歧义标注 | 单测 TableTextsUnitTypingTest |
| HTML_TABLE_ENABLED | HTML 分支启用（Jsoup 1.22.2 `<table>`，无新依赖） | 可选分支正常解析 | HtmlTableExtractorTest |

## 8. D13 执行期修订记录（2026-11-24 P4 演练 R6→R7）

1. PdfTableExtractor 增加 pendingRow 表头前置吸收（§3）+ `splitCells(row, gap)` 重载——根因：T3-a2 宽排表头 24pt 切分成单 cell 被区域条件丢弃导致整表上移 + ROW_MISSING（探针 `ProbeD41Diff` 同包反射实证）。
2. UnitTyping 增加 DIMENSION_SUFFIX 尺寸否决（§6）——根因：`25x52x15` 误判 number/unit=`x52x15`。
3. 夹具 a2 合并格文本 `Merged BC across` → `Merged Cells`——根因：原文本右缘 e≈197 物理压住 D2v@190 锚点粘连成单 run，MERGED_CELL 旗标失真；改后 e=150 越 C 锚 142、距 D2v gap 40，受控构造语义恢复。fixtures+truth 已全量重生成（104 cells）。
4. 修订后复验：探针 a2 表头/三旗标全对、单测 71/71、演练九场景全 PASS、对账 113 cells diffs=0。
