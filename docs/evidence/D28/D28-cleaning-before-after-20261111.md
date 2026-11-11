# D28 文本清洗前后对照

## 汇总

| 指标 | 原始页文本合计 | 清洗后规范化文本 |
|---|---:|---:|
| Unicode 码点数 | 9,289 | 9,215 |
| 页面数 | 11 | 11 |
| 段落数 | - | 63 |

清洗后长度包含跨页和跨段落之间的单个空行分隔，不是 Token 数。

## 第 1 页原始提取片段

```text
Saved from manuals.plus.
Open the live page for links to other documents and user manuals.
Manuals.plus /
› Canon /
› Canon PIXMA MP500 All-In-One Photo Printer, Copier, and Scanner User Manual
Canon MP500
Canon PIXMA MP500 All-In-One Photo Printer, Copier, and
Scanner User Manual
1. INTRODUCTION
This manual provides essential instructions for the safe and efficient use of your Canon PIXMA MP500 All-In-One device.
```

以上内容中的换行符在 JSON 中实际为 CRLF。

## 第 1 页清洗后片段

```text
Saved from manuals.plus.
Open the live page for links to other documents and user manuals.
Manuals.plus /
› Canon /
› Canon PIXMA MP500 All-In-One Photo Printer, Copier, and Scanner User Manual
Canon MP500
Canon PIXMA MP500 All-In-One Photo Printer, Copier, and
Scanner User Manual
1. INTRODUCTION
This manual provides essential instructions for the safe and efficient use of your Canon PIXMA MP500 All-In-One device.
```

## 观察

- CRLF 已统一为 LF。
- 行尾空白和连续无意义空行被清理。
- 标题、列表符号、数字、单位、英文和中文未被删除。
- 第 1 页识别出 7 个段落，包括标题和列表结构。
- 原始文本和清洗后文本在 `CleanedPage` 中同时保留。
- 机器可读逐页对照：`docs/evidence/D28/D28-chunking-comparison.json` 的 `cleaningPages`。
