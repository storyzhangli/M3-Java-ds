# D40_state_R01

- 迭代：D40/R01 文档更新删除与一致性修复（D40_DUDACR, com.zl.d40, :9090）
- 日期：2026-11-23
- 基线：HEAD ec86b75（用户提交骨架）→ 本文件所在提交为 R01 迭代提交
- 冻结参数：docs/evidence/D40/probe/sync-params.json（KP1–KP4、原因码六码、指纹五元组、漂移四类+两新增）

## 阶段状态

| 阶段 | 状态 | 证据 |
|---|---|---|
| P0 骨架/探测 | DONE（骨架由用户提交；KP2 盘点+漂移裁决；指纹门禁达成） | probe/kp2-*.txt, sync-params.json |
| P1 同步状态机 | DONE（14 类 + 2 SQL，compile 通过） | src/main/java/com/zl/d40/knowledge/sync/ |
| P2 删除闭环/指针/钩子 | DONE | DocumentVectorIndexService 改造 + Repository 扩展 |
| P3 审计接线 | DONE（3 Controller + yml 段） | 本次提交 diff |
| P4 一致性核查扩展 | DONE（View +2 清单，recon 区块5/6） | VectorReconciliationService diff |
| P5 测试 | DONE 组件层 18/18 绿；E1–E5 执行层脚本已写未执行（MySQL 弹窗阻塞） | src/test/.../sync、vectorstore、vectorindex/service；scripts/run-e1-e5-execution.ps1 |
| P6 回归 | DONE（质量列 diff=0；数据集 SHA 一致；修复评测器 out 缺类） | P6-regression-qualitycols-diff.txt |
| P7 交付 | DONE（双文档 + 状态文件 + 迭代提交，不 push） | temp_docs/D40/技术文档 + 本文件 |

## 缺陷与修复记录

1. classify 裸子串 "io" 误命中 violated（测试暴露，主代码根因修复 + T-SM-10/12 回归锁定）。
2. SyncRetryHandler 双抽象方法不兼容 lambda（P1 接口 vs P2 注册端）→ 匿名类实现。
3. Grep 工具索引路径失真（reconcile 包实际为 service/dto/model 分包）→ 以文件系统为准修正全部引用。
4. D37 评测器 out 目录缺内部类且增量判定跳过编译 → 全量 javac 重编。

## OPEN 项（解阻后待办）

- [ ] 应用 sql/d40 两 DDL；核验 d40_document_sync / d40_admin_audit 存在性
- [ ] 运行 scripts/run-e1-e5-execution.ps1（含 access=true 审计链真实启动验证）
- [ ] vector_chunk_index / vector_index_task 的 d40-/d40t- 前缀分布核验
- [ ] 指纹五元组 PRE/POST 回归（执行层演练后）
