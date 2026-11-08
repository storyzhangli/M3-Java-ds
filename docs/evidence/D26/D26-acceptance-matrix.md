# D26 验收矩阵

| 验收项目 | 结果 | 证据 |
|---|---|---|
| 项目扫描 | 通过 | `D26-baseline.md`、`D26-baseline-index.md` |
| Schema | 通过（最小复用并记录差异） | D25 Schema 引用、`D26-baseline.md` |
| 文档登记 | 通过：`POST /api/v1/knowledge/documents` | Controller/Service + 集成测试 |
| 文件导入 | 通过：`POST /api/v1/knowledge/documents/{id}/import` | Controller/Service + 集成测试 |
| 状态流转 | 通过：PENDING/PROCESSING/COMPLETED/FAILED | 状态流转测试 |
| 服务端大小校验 | 通过：Servlet + 业务双层 | 两个 SizeLimit 测试 |
| 类型/编码校验 | 通过：Tika + UTF-8 REPORT | 集成/Parser 测试 |
| 重复导入 | 通过：SHA-256 + unique + 幂等 | 重复和并发测试 |
| 自动化测试 | 通过：208 项全量 | `D26-test-summary.md` |
| Maven 编译 | 通过 | `D26-compile-01.log`、测试编译 |
| 技术文档 1 | 已生成 | `docs/proj_docs/D26-文档登记与导入功能实现全量说明.md` |
| 技术文档 2 | 已生成 | `docs/proj_docs/D26-文档导入与基础解析技术原理及操作实践指南.md` |
| 测试证据 | 已归档 | `docs/evidence/D26` |
| 原始日志 | 已归档 | `logs/D26` |
| 临时文件 | 已归档 | `docs/temp_docs/D26` |

## 状态说明

- 已完成并测试通过：登记、导入、状态、校验、去重、并发。
- 已实现但未做真实生产集群验证：唯一约束并发控制（本机 MySQL 实测）。
- 尚未完成：Chunk、Embedding、VectorStore、完整 RAG、D25 发布生命周期/ACL。
