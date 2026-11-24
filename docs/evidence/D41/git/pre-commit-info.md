# D41 R01 提交前核验（git\pre-commit-info.md）

- 提交前 HEAD：e333e35f4bf674c0e1faae60a0b84167d8376579「D41 项目基础骨架」
- 计划提交信息：`D41 项目迭代R01 设备手册表格与结构化信息`；**不 push**（任务书 §6）
- 暂存文件数：117（git add 后 git rm --cached 排除 docs/temp_docs/D41/probe-out\ 编译 .class 产物 4 件，符合"源码交付物"纪律；credentials-d41t-*.json 5 件经 reset 排除，明文 token 仅存 temp_docs 本地）
- 卫生扫描（git diff --cached 全量）：5 个夹具 token 明文 **零命中**；`sk-` 零命中；`Bearer `+长随机串零命中（应用日志 ACCESS_LOG 头脱敏 `"authorization":"*****"` 实证）
- 构成：main 19+1（table 包+白名单 5 改动中 main 4 件+yml）；test 11 类（迁移 7+新 4）；evidence\D41 全目录（probe/test/ddl/r01-exercise/extract/exceptions/linkage/security/sync/cleanup/capability/cost/regression/scripts/iteration_records/README）；proj_docs D41-01/02；temp_docs\D41 源码+夹具+真值+DDL+cp.txt+任务书（add -f 先例）
- 本文件自身在核验输出后追加暂存，故上方 117 不含本文件与最终 stat 快照
- stat 快照：见同目录 stat.txt 与提交后 `git show --stat HEAD`
