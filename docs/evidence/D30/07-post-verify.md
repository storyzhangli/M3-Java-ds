# D30 证据 07：验收后只读复核（数据安全）

- 脚本：`docs/temp_docs/D30/post-verify-readonly.py`（paramiko SSH，只读）
- 日志：`logs/D30/post-verify-20261113_191359.log`

## 复核结果（2026-11-13 19:13）

| 检查项 | 结果 |
|---|---|
| 主表 `public.vector_store` 行数 | **0**（未被任何测试数据污染，未做任何清理动作） |
| smoke 表 `d30_vector_smoke_test` 剩余行数 | 0（测试数据已按 d30TestMarker 标记清理） |
| 主表索引 | spring_ai_vector_index (hnsw vector_cosine_ops) + vector_store_pkey 完好 |
| smoke 表索引 | d30_vector_smoke_hnsw_idx (hnsw vector_cosine_ops) 保留为环境证据 |

红线遵守情况：未 prune、未删除任何容器/镜像/卷、未 DROP 任何表或扩展、未执行
`remove-existing-vector-store-table`、未修改 docker-compose 端口与挂载。
