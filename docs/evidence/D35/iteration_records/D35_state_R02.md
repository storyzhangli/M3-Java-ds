# D35 迭代状态

## 需求编号
D35-R02（Phase3–5 执行与验收结果）

## Phase3 编码（已完成）
- 新增 5 个主代码：CitationText / ChunkSources / VectorMetadataBuilder / UrlSafetyPolicy / CitationExistenceValidator。
- 修改 18 个主代码（DTO/视图/mapper/编排/校验/入库/前端），见文档 1 修改清单。
- `mvn compile` EXIT=0。

## Phase4 测试与验证（已完成）
- 从零建立 9 个测试类（含 1 个 fixture + 8 个测试类）。
- `mvn clean test` **BUILD SUCCESS，EXIT=0**；`Tests run: 47, Failures: 0, Errors: 0, Skipped: 0`。
- 前端 static 目录 `innerHTML` 扫描 0 命中。
- 产出 `citation-evaluation-dataset.json`（14 条）+ `evaluation-metrics.json`（7 指标）。

### 关键修复记录（真实失败→修复→复验）
1. `ChunkStrategy.PARAGRAPH` 不存在 → 改 `FIXED_LENGTH`（枚举值只有 FIXED_LENGTH/STRUCTURE_AWARE）。
2. 页码缺失期望 null 实际 `[]` → `toKnowledgeChunk` 将空 pageNumbers 规范化为 null（缺失不补造）。
3. 空 query 期望 HTTP 400 实际 200 → 项目 HTTP 层恒 200，业务码 4001 表达，测试断言改为 code=4001。

## Phase5 文档与证据（已完成）
- 文档 1：`D35-01-引用溯源与返回契约-项目迭代全量信息与操作.md`
- 文档 2：`D35-02-引用溯源与返回契约-技术文档说明与实践指南.md`
- 证据：D35/00-env-probe.txt、citation-evaluation-dataset.json、evaluation-metrics.json、iteration_records/D35_state_R01.md、temp_docs/D35/mvn-clean-test.txt

## 未执行/待授权
- Level 5 真实 pgvector 端到端：需隔离可控数据，避免真实文档/历史向量变更，待用户授权。
- 存量 paragraphIndex 回填：= 重新切分入库（破坏性），本轮只提供方案不执行。

## Git 状态
- 提交范围仅 D35 模块 + docs 变更，排除 target/surefire-reports/密钥。
- HEAD=e666212 起，本轮工作区变更待提交（见提交记录）。
