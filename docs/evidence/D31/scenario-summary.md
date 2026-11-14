# D31 六场景与补充检查证据摘要

> 运行日期：2026-11-14
> 测试类：`com.zl.d31.vectorindex.VectorIndexRealIntegrationTest`
> 开关：`D31_VECTOR_INDEX_IT=true`（未设置时整类跳过，不产生误报）
> 凭据：`D31_MYSQL_PASSWORD`、`D31_PG_PASSWORD` 由环境变量注入，未写入任何文件
> 结果：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`（详见 `surefire-vector-index-integration.xml`）

## 环境实测

| 项 | 值 |
| --- | --- |
| MySQL | 9.7.0-commercial，库 `ai_db` |
| PostgreSQL | 18.7（Debian 18.7-1.pgdg12+2） |
| pgvector | 0.8.7 |
| 主向量表 | `public.vector_store`，`vector(384)`，HNSW `vector_cosine_ops`，测试前后均为 0 行（未被写入） |
| 测试向量表 | `public.d31_it_vector_store`（独立表，自动幂等建表，只清理 `d31-it-` 前缀数据） |
| MySQL 测试表 | `vector_index_task`、`vector_chunk_index`（由 classpath `db/schema-vector-index.sql` 幂等建表） |

## 六种场景

| 场景 | 测试方法 | 执行方式 | 数据库证据 | 结果 |
| --- | --- | --- | --- | --- |
| 1 新增导入 | `firstImportWritesAllChunks` | `indexChunks(doc, v1, 3 块)` | PG=3、关联=3、INDEXED=3；相似度检索 top1 命中本文档 | 通过 |
| 2 重复导入 | `repeatedImportIsIdempotent` | 同一载荷再导入一次 | `idempotent=true`、`processed=0`、PG 仍=3、任务仍 1 行 | 通过 |
| 3 更新 | `newVersionSwitchesAndCleansOldVersion` | 先 v1 三块，再 v2 三块 | PG(v2)=3、PG(v1)=0、v1 状态 SUPERSEDED | 通过 |
| 4 删除 | `deleteByDocumentRemovesEverythingAndIsRepeatable` | 索引后整篇删除，再删一次 | PG=0、关联=0、任务 0；第二次无副作用 | 通过 |
| 5 失败重试 | `failedWriteIsRecordedAndRetryable` | 包装 Embedding 模型第 2 次调用抛错（batch=2） | 第一次 PARTIAL/EMBED_WRITE/EMBED_WRITE_FAILED、PG=2；重试后 COMPLETED、PG=3、INDEXED=3 | 通过 |
| 6 部分失败恢复 | `stuckTaskIsRecoveredIdempotently`、`incompleteSnapshotIsNotRebuilt` | 完整快照重跑；不完整快照只标记 | 完整快照 → COMPLETED 且 PG 仍=3；不完整 → FAILED/NO_COMPLETE_SNAPSHOT | 通过 |

## 补充检查

| 检查 | 测试方法 | 证据 | 结果 |
| --- | --- | --- | --- |
| 同版本内容变化 | `sameVersionContentChangeReplacesRows` | 3 块改 2 块：PG=2、关联=2、DISTINCT=2 | 通过 |
| 迟到任务 | `staleVersionWriteIsRejected` | v2 活动时回写 v1 → 抛出"迟到写入"，v1 保持 SUPERSEDED | 通过 |
| 写入成功但状态更新失败 | `stuckTaskIsRecoveredIdempotently` | 手工置 INDEXING + 超时 → 恢复为 COMPLETED，PG 仍 3 | 通过 |
| 连接中断恢复 | `connectionFailureIsRecoverable` | 错误口令连接：FAILED、PG=0；恢复连接后 COMPLETED、PG=3、DISTINCT=3 | 通过 |
| 并发导入 | `concurrentImportProducesNoDuplicates` | 双线程结果 `[ok, ok]`；最终 PG=3、DISTINCT=3、任务 1 行 | 通过 |
| 重复删除 | 同场景 4 | 第二次删除 0 副作用 | 通过 |
| 孤儿 / 缺失 / 数量核对 | `reconciliationDetectsAndCompensates` | dryRun `orphan=1 missing=1 mismatch=1`；补偿后 `issues=0`、PG=3 | 通过 |
| 空密码 fail-fast | `PgVectorStorePropertiesTest#requireValidRejectsBlankPassword` | 抛出含 `PG_VECTOR_PASSWORD` 的异常 | 通过 |

## 首轮失败与修复证据

| 轮次 | 结果 | 发现的问题 | 修复 |
| --- | --- | --- | --- |
| 第 1 轮 | 12 项中 2 项失败 | ① 同版本清理在核对之后，`pg=5 expected=2`；② 任务行 NOT NULL 列传入 null | ① 清理上移到核对前；② Repository 统一空值归一 |
| 第 2 轮 | 12 项全部通过 | — | — |

原始日志：`logs/D31/integration-test.log`（第 1 轮）、`logs/D31/integration-test-run2.log`（第 2 轮）、`logs/D31/integration-test-final.log`（复跑确认）。

## 单元测试与回归

| 范围 | 数量 | 结果 |
| --- | --- | --- |
| 稳定 ID / 配置 / 指纹 | 13 | 全部通过 |
| 既有 vectorstore 回归 | 12 | 全部通过 |

## 构建

| 阶段 | 结果 |
| --- | --- |
| `mvn -pl D31_VSUAI -am compile` | BUILD SUCCESS，194 源文件 |
| `mvn -pl D31_VSUAI -am test-compile` | BUILD SUCCESS，84 测试源文件 |
