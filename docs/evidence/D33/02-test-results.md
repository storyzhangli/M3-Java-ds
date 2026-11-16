# D33 测试结果证据（Phase4 真实执行）

生成时间：2026-11-16 23:18（Asia/Shanghai）
命令：`mvn.cmd -f D33\pom.xml clean test`（Maven 3.9.16 / JDK 21.0.7）
surefire-reports 聚合：suites=11，tests=14，failures=0，errors=0，skipped=3

## 通过（11 个测试，九类场景全覆盖）

| 测试类 | 场景 | 结果 |
| --- | --- | --- |
| RagAnswerableEndToEndTest | 文档可答 + 来源可追溯 + Prompt 含防注入约束 | 1 通过 |
| RagIrrelevantQuestionTest | 无关问题（全低于 min-score）不强行拼答、模型零调用 | 1 通过 |
| RagEmptyRetrievalTest | 空检索 NO_HIT + ChatModel 零调用可核验 | 1 通过 |
| RagNoUsableContextTest | 预算容纳不下 → NO_USABLE_CONTEXT、模型零调用 | 1 通过 |
| RagGenerationTimeoutTest | 生成异常 → GENERATION_FAILED，来源信息不丢 | 1 通过 |
| RagRetrievalFailureTest | 检索基础设施异常 → RETRIEVAL_FAILED（≠空检索） | 1 通过 |
| RagInvalidCitationTest | [S99]/[S5] 非法引用被清理，droppedUnknownMarkers=2，无虚假来源 | 1 通过 |
| RagDuplicateChunkTest | 同 chunkId 去重（保留高分），不重复拼接 | 1 通过 |
| RagBudgetOverrunTest | 预算 600 字符截断、usedChars≤600、来源映射不丢 | 1 通过 |
| RagPropertiesTest | 默认值符合任务书 + 非法组合 fail-fast | 2 通过 |

## 门控跳过（3 个，Level 5 真实端到端）

| 测试类 | 门控条件 | 状态 |
| --- | --- | --- |
| RagE2eRealIntegrationTest（3 用例：入库/问答/清理） | 环境变量 D33_RAG_E2E=true | skipped（未设置） |

## Level 5 阻塞说明

真实端到端需同时满足：
1. `D33_RAG_E2E=true`
2. `PGVECTOR_DB_PASSWORD`（PG 192.168.200.153 口令，按用户偏好不持久化、需时由用户提供）
3. `AI_API_KEY`（DeepSeek 真实调用）

三者未提供时 Level 5 标记阻塞，禁止以 Mock 冒充真实端到端。
真实检索路径（SimilarityRetrievalService → pgvector）在 D32 已实证（12 个真实集成测试全绿，
commit 469c178），本次复用的检索通道未经 Mock。
