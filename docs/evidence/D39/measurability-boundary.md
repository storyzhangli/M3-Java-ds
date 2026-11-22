@(#) D39 R01 可测性边界（measurability-boundary）

== 本轮不可测/未测项与原因 ==
1. rag/chat 生成端点端到端权限断言（C10 集成层）：与 §5 结构性零远程纪律冲突（真实 DeepSeek 调用预算余 1
   保留给生成态迭代）。以三层替代证据闭环：RagPromptBuilder/检索服务继承链单测 + 检索层同实现复用证明
   （rag 入口内部走同一 SimilarityRetrievalService/HybridSearchService 权限装配点）+ 矩阵全入口零越权。
   状态记 NOT RUN（授权纪律原因），非实现缺失。
2. PG 不可达故障注入端到端（C16 集成层）：共享 PG 实例（192.168.200.153 容器）停机重启属破坏性操作，
   禁 Docker 操作纪律下不执行。以 AuthContextFilterTest fail-closed 单测（AUTH_STORE_UNAVAILABLE 两例）
   + 配置装配审查替代。状态记 NOT RUN。
3. SSE 流式路径权限一致性：D39 端点面无 SSE 变更点，流式与同步一致性列为 D40 候选。
4. 跨机复现（L5）：单机环境，N/A 如实标注。
5. two_ 空间真实权限端到端：TWO_*=false 装配关闭 + 预算不动（余 1），谓词代数经共享的
   AccessPolicyService 单测覆盖；端到端留 D40 授权后执行。

== 口径边界 ==
- "台账 17 条 SHA 不变"：docs\evidence\D38\calls\call-ledger.jsonl 文件级 SHA（9A620A58…E10E）；
  DB 侧 two_vector_chunk_index=8 行、two_vector_store=8 行（11-21 后零写入，last_created 佐证）。
- 遗留无标签数据：PG 30 行/22 distinct docId（D39 视角全部为权限空间外，任何 principal 含 ADMIN 零命中，
  T9 断言）；MySQL 3 行缺键文档原样未动。
- 倒排基线指纹 a6a6bd4f…（30 行态）为进程内存态，重启即由 HybridIndexLoader 重建；
  本轮 rebuild 后逐字符一致。
