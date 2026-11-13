# D30 证据 03：环境检查（2026-11-13 实测，只读）

- 脚本：`docs/temp_docs/D30/ssh-pg-vector-check.py`（paramiko SSH，只读）
- 日志：`logs/D30/env-check-20261113_185024.log`
- Windows→VM 连通性：TCP 5432 = True，ping = True

## Docker 容器（compose ps）

```
NAME            IMAGE                    STATUS                 PORTS
pgvector-pg18   pgvector/pgvector:pg18   Up 3 hours (healthy)   0.0.0.0:5432->5432/tcp
```

inspect：State=running，Health=healthy；数据卷 `pgvector-stack_pg18_data` 挂载正常。

## PostgreSQL / pgvector（psql 实测）

| 检查项 | 结果 |
|---|---|
| 版本 | PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) |
| 当前库/用户 | spring_ai / postgres |
| 扩展 | `vector 0.8.7` 已安装 |
| vector_store 列 | id uuid / content text / metadata json / embedding **vector(384)** |
| 主索引 | `spring_ai_vector_index USING hnsw (embedding vector_cosine_ops)` |
| public 表清单 | d30_vector_test（用户此前实验表，未触碰）、vector_store |

## 结论

容器健康、PG 18.7 运行、pgvector 0.8.7 就绪、主表 schema 与 Spring AI 1.1.8 默认完全一致
（vector(384) + HNSW cosine）——L3 前置条件满足，本次未对数据库执行任何 DDL。
