@(#) C20/§5 零远程与台账不变证明（D39-R01）
1) 配置面：APP_ACCESS 测试实例注入 TWO_PGVECTOR=false/TWO_EMBEDDING=false；生成端 ai.mode 默认 mock；
   检索 query 向量化=local-hash 384（配置 provider 分支实测，日志无百炼调用行）
2) 日志面：本轮三实例日志深扫真实远程调用行：
D39-r01-run3.log real_remote_call_lines=0
D39-r01-regress8080-post.log real_remote_call_lines=0
D39-r01-skel8080.log real_remote_call_lines=0
   （既有匹配仅 TwoPgVectorConfiguration 启动装配 INFO 一行，为 bean 装配记录非网络调用，骨架同态）
3) 台账面：docs\evidence\D38\calls\call-ledger.jsonl
   sha256=9A620A583FA0950993785784381F94B8A950FE3211C41E025421377DEC95E10E（=冻结值 9A620A58…E10E 一致）；行数=17；mtime=2026-11-21 18:55:46（D38 时代，本轮零触碰）
结论：DeepSeek calls=0 / 百炼 calls=0 / Token=0；预算 18 已耗 17 余 1 未动。
