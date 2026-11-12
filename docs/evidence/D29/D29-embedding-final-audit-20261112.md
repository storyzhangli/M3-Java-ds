# D29 Embedding 最终审计

## 实现

- 本地 Spring AI EmbeddingModel。
- 外部化模型、维度、长度、索引和 API Key 配置。
- 空/超长输入拒绝。
- 向量维度与数值校验。
- 有限重试复用现有 RetryTemplate。
- artifact 保留 documentId、title、version、页、段落、Chunk 索引和 input hash。
- vectorSpaceId 与 indexVersion 隔离。

## 验证

- 真实本地调用：4 个教学 Chunk + 17 个真实 PDF Chunk。
- 最终全量：255 项，0 失败，0 错误，1 跳过，日志为 `logs/D29/D29-full-regression-02.log`。
- 跳过项：既有真实 DeepSeek 烟雾测试。
- OpenAI Embedding 未真实调用。

## 安全

- 未读取、输出、落盘或提交真实 Embedding/DeepSeek Key。
- 工作区严格 Key 扫描为 0。
- Embedding 接口和向量未进入通用访问日志。
- API Key 不在模型描述、响应或 `toString` 中。

## Git

- 分支：`master`
- HEAD：`ada34418bdece0ca3102d4683bc1d8bc9665cb1f`
- 暂存区为空。
- 未执行 commit、push 或 reset。

## JAR

- 打包日志：`logs/D29/D29-package-02.log`
- 字节：114,783,474
- SHA-256：`09025ACCA288ECE8805EAD094BDDA4E1944EEF9DB8E3D7B5CFF548F7724D1362`
- JAR 内容核对：包含 `BOOT-INF/classes/com/zl/d29/knowledge/embedding/` 下实际编译类。
