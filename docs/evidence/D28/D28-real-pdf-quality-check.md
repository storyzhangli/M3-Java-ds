# D27 真实 PDF 原文与提取文本抽查

## 样本

| 项目 | 值 |
|---|---|
| 文件名 | `D27-佳能用户手册-DB-测试素材.pdf` |
| 本地来源 | `E:\AI\project\AI-Learning-one\M3-Java\docs\proj_docs\D27-佳能用户手册-DB-测试素材.pdf` |
| 公开来源参考 | `https://manuals.plus/asin/B000AYFP6A.pdf` |
| PDF 标题 | Canon PIXMA MP500 All-In-One Photo Printer, Copier, and Scanner User Manual |
| PDF 元数据版本依据 | CreationDate `D:20261024080126Z` |
| 文件大小 | 1,509,800 字节 |
| SHA-256 | `566a1f050d7cf512c750d1c0f5e699438d839dca7b0cf1266ef282c8dfbee83a` |
| 页数 | 11 |
| 页面尺寸 | A4，595 x 842 pt |
| 解析器 | Apache PDFBox 3.0.7 |
| 提取总字符数 | 9,289 |

## 页级结果

| 物理页 | 字符数 | 原文观察 | Java 提取结果 | 差异/说明 |
|---:|---:|---|---|---|
| 1 | 1518 | 站点提示、面包屑、标题、Introduction、Safety、Package Contents | 三个主章节和标题均存在 | 字体、颜色、灰色背景和圆点图标不保留 |
| 2 | 1153 | Figure 3.1 图片、图注、Setup 八步列表 | `Figure 3.1`、`SETUP`、`Unpacking` 等文本存在 | 图片本身不 OCR；图注和正文文本可提取 |
| 3 | 135 | 快速安装示意图及图注 | Figure 4.1 图注存在 | 以图为主，提取文本较少 |
| 4 | 303 | 后视图、图注、Basic Operation 开头 | 图注与正文存在 | 图片结构不重建 |
| 5 | 1038 | 控制面板图片、按键列表和 Copy 说明 | 按键列表与说明存在 | 列表顺序基本符合阅读顺序 |
| 6 | 653 | 复印界面图、Scanning 步骤 | `5.3. Scanning Documents and Photos` 等存在 | 图像内容不识别 |
| 7 | 534 | 扫描仪图片、Computer Printing | 图注与打印说明存在 | 文本顺序按内容流/版面抽取 |
| 8 | 497 | 墨盒图片、Replacing Ink Cartridges | 步骤文本存在 | 无图片视觉信息 |
| 9 | 1181 | 内部结构图、Maintenance | 维护说明存在 | 无表格边框/图片结构 |
| 10 | 1815 | 故障表、Specifications 列表、Warranty | 表格文字、规格和保修正文存在 | 表格单元内容可见，但不保留单元格结构 |
| 11 | 462 | 表单尾部、Privacy details、站外链接 | 对应文本存在 | 页面大部分为空白，仍有有效文本 |

## 重点断言

- 第 1 页包含 `Canon PIXMA MP500`、`INTRODUCTION`、`SAFETY INFORMATION`。
- 第 2 页包含 `Figure 3.1`、`SETUP`、`Unpacking`。
- 第 10 页包含 `Cannot scan from`、`Error message on`。
- 第 11 页包含 `Privacy details`。
- 11 个页面记录物理序号严格为 1 到 11，没有整文合并。

## 可视化抽查

- 已渲染并目视检查第 1、2、10、11 页。
- 第 1 页是封面/介绍页，第 2 页包含图片与 Setup 列表。
- 第 10 页包含故障表、规格列表和保修信息。
- 第 11 页为表单尾部及链接，页面大部分留白。

## 能力边界

- 本抽取验证文本内容与物理页面归属，不验证版面像素级重建。
- 不提取图片中的文字，不执行 OCR。
- 不恢复表格单元格、合并单元格、栏位边框或多栏阅读顺序。
- 印刷页码没有从本样本确认；项目只保存 PDF 物理页序号。

## 机器证据

- Java PDFBox 逐页全文：`docs/evidence/D27/D27-java-pdf-extraction.json`
- 独立 pypdf 页级摘要：`docs/evidence/D27/D27-real-pdf-pages.json`
- 渲染图：`docs/temp_docs/D27/render/`
