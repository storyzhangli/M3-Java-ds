# D25 评测数据校验摘要

- 校验时间：2026-11-07 22:38:10 +08:00
- 数据集：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D25\D25-rag-evaluation-dataset.json`
- JSON Schema：通过
- 用例总数：21
- caseId 唯一：是（21）
- 问题文本唯一：是
- 真实语料文档：3
- 真实语料 Chunk：8
- 期望证据引用：7

## 类别分布

| 类别 | 数量 | 最低要求 |
|---|---:|---:|
| answerable | 7 | 6 |
| unanswerable | 4 | 4 |
| permission | 4 | 4 |
| fault-code | 4 | 4 |
| boundary | 2 | 2 |

## 校验结论

- 格式与必填字段：通过
- Document/Chunk JSON Schema 语法：通过
- ID/问题唯一：通过
- 四类重点场景覆盖：通过
- 真实文档/Chunk 证据引用：通过
- 权限用例不暴露证据 ID：通过
- RAG 端到端运行评测：未执行

## 产物指纹

| 文件 | SHA-256 |
|---|---|
| `D25-rag-evaluation-dataset.json` | `A1C4B615C091374CC36DB78E15E6A478B5D489B4DE314C783A8EA5BE8F1B870F` |
| `D25-knowledge-document-schema.json` | `8BD630FA3945936729552AFBD2566B07376E6FF79BED692163D1FB8D2F4D8380` |
| `D25-chunk-schema.json` | `711A0A0FD636970CB9CDA2527AA5F222F13CE8C3EC375C699F58B47BAA693AD9` |
