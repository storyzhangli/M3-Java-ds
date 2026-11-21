# D38 two_ 基线冒烟摘要（20261121-185541）

- 后端：TWO_BAILIAN / qwen3.7-text-embedding / 1024 维 / two_vector_store
- 冒烟：PASS=6 FAIL=0 BLOCKED=0 NOT RUN=0（合计=6）
- 幂等复跑：PASS（复跑 API 调用=0）
- 台账：2 → 17（本轮新增 15，预算 18，未超）
- 本地回归质量字段一致：True
- 密钥扫描：PASS

| 问题 | 场景 | 判定 | 命中数 | Top 分数 | 备注 |
|---|---|---|---|---|---|
| QA-001 | ANSWERABLE | PASS | 5 | 0.8668 |  |
| QA-004 | ANSWERABLE | PASS | 5 | 0.8146 |  |
| QU-001 | UNANSWERABLE | PASS | 5 | 0.4359 | availability-only（无期望 Chunk） |
| QU-003 | UNANSWERABLE | PASS | 5 | 0.727 | availability-only（无期望 Chunk） |
| QT-001 | TERMINOLOGY | PASS | 5 | 0.7515 |  |
| QF-001 | BOUNDARY | PASS | 5 | 0.5781 | availability-only（无期望 Chunk） |

> 远程向量非确定性：本基线以命中文档/逻辑 Chunk 与 Top-K 顺序为对比键；分数与耗时只报分布，不与 D37 本地基线混算。
