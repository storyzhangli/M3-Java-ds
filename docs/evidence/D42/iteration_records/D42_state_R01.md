# D42 状态记录 R01（2026-11-25）

- 基线：7c9d382（D42 骨架，F01 漂移裁决）；链 4db51d3→7c9d382；**本轮未 commit**。
- 完成：P0 实扫 F01–F11 / P1 KP1–KP4 冻结（四 json）/ P2 迁移 95/95 / P3 主包 16 类 / P4 实验包 8 类+38 单测 / P5 实验轮真实执行（12.37s）/ P6 清理对账+安全证据 / P7 双文档。
- 回归终值：133/133, Failures 0, Errors 0, Skipped 0（test profile）；实验轮 IT 单类通过。
- 决策：R1/R2 NOT_ADOPTED（G-A Δ=0，基线 HitRate@5=1.0 饱和）；R3 BLOCKED（mock 无授权，预设路径）；框架与端点在位保留。
- 真实缺陷修复 2 处：BailianTextReranker failCode 残留（重试成功路径）、Runner effTopN 候选收敛。
- 存储：PG 30 / rr_rows 0 / d42_sync 0 / Redis 1 / d41 侧不变（post-cleanup-inventory.txt）。
- 下一步候选：R3 授权轮（G-D 实算）、未饱和语料重测矩阵、重排接入证据组装链（需新任务书）。