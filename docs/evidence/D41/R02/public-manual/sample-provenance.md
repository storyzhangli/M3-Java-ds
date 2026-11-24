# D41 R02-01 真实公开手册来源链（sample-provenance）

## 1. 样本选定过程

| 步骤 | 动作 | 结果 |
| ---- | ---- | ---- |
| 1 | 检索候选：Danfoss / WIKA 等厂商公开手册（含故障码或参数表、文本型 PDF、官方可访问） | 候选：Danfoss FC302 系列操作指南多个版本、WIKA 说明书等 |
| 2 | 评估工程适配（变频器故障码 + 电气参数表 + 中文）与官方直链可达性 | 选定 `MG38A141`（VLT AutomationDrive FC 302 操作指南，315–710 kW 机箱 E） |
| 3 | `Invoke-WebRequest` 直连官方 URL 下载 | HTTP 200，12 427 018 字节 |
| 4 | `Get-FileHash SHA256` | `9041B14E2EA9DBC55AEDAEE0612FB0B98075CE90D5DD6BF629A93AB03AA4747A` |
| 5 | 文本层验证（PDFBox `PDFTextStripper`） | 112 页 / 129 628 字符，确认文字型 PDF（非扫描件、未做 OCR） |

## 2. 溯源要点

- **名称/厂商/型号**：《VLT AutomationDrive FC 302 操作指南》，Danfoss A/S，315–710 kW 机箱规格 E，文档号 MG38A141。
- **版本/日期**：页脚版权行 `Danfoss A/S © 01/2017`（逐页可见）；PDF 元数据 producer=GPL Ghostscript 10.03.1、creationDate=2024-06-09，为 Danfoss 官方站点对 2017 版手册的压缩重制，内容版本以页脚 MG38A141 01/2017 为准。
- **URL**：https://files.danfoss.com/download/Drives/MG38A141.pdf （Danfoss 官方下载域，公开无需授权）。
- **SHA-256 / 字节数**：见 `manual-sha256.txt` 与 `source-metadata.json`。
- **版权处置**：PDF 不提交 Git；`temp_docs/D41/manuals/` 已写入 `.gitignore`；证据目录仅存元数据、哈希、解析结果与短引用。

## 3. 执行入口（可复现）

```powershell
# 依赖 classpath 与编译产物（target/classes）就绪后：
java -Dstdout.encoding=UTF-8 -cp "<target/classes>;<deps>" `
  docs/evidence/D41/R02/scripts/ProbeD41Manual.java `
  "<本地 PDF 路径（不入仓）>" "docs/evidence/D41/R02/public-manual"
```

- 探针：`scripts/ProbeD41Manual.java`（只读解析，不落库、不走 HTTP、路径参数化）。
- 原始输出：`public-manual-verification.log`（stdout 全文，含 PDFBox `No Unicode mapping` 字体告警）。
- 解析结果：`manual-tables.tsv`（411 张候选表全量行）、`extract-result.json`（聚合统计）。

## 4. 真实文档暴露的已知损失（如实记录，不掩盖）

- 该 PDF 为 Ghostscript 压缩重制版，部分内嵌子集字体（`JDPEEV+ArialMT` 等）**缺失 ToUnicode 映射**（stderr 中大量 `No Unicode mapping` 告警），文本层存在数字/词形噪声，例如表 9.1 中原文 `685` 在文本层为 `885`（字体码位映射损坏所致），**与列分桶算法无关**——分桶只做归属，不改动文本提取值。
- 手册含大量双栏正文与图文混排，区域启发式以召回优先，会把部分两栏排版读成候选表（如第 62 页警告/报警条目为标题式段落而非表格网格，条目文本被保留于 cell 中）——这是既有 KP 冻结口径（recall-biased）行为，非 R02 回归。
