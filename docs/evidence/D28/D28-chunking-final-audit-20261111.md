# D28 文本清洗与 Chunk 切分最终审计

## 实现

- 独立 TextCleaner。
- Unicode 码点长度。
- 固定长度和结构感知切分。
- Chunk Size、Overlap、最小/最大长度配置。
- 页面和段落来源列表。
- 页面结果 REST 预览与双策略对比。
- 不触发 Embedding 或 VectorStore。

## 真实样本

- 11 页 Canon PDF。
- 原始 9,289 码点。
- 清洗后 9,215 码点。
- 63 个启发式段落。
- 固定策略 25 Chunk，平均 426.20。
- 结构策略 17 Chunk，平均 598.53，全部在段落/文档末尾结束。
- 两种策略 Overlap 均为 60 码点。

## 测试

- 最终全量：235 项，0 失败，0 错误，1 跳过。
- 唯一跳过项为真实 DeepSeek 烟雾测试。
- 解析、Chunk、数据库页面和 REST 集成均通过。

## 文档

- `docs/proj_docs/D28-文本清洗与Chunk切分功能实现全量说明.md`
- `docs/proj_docs/D28-文本清洗与Chunk切分技术原理及操作实践指南.md`

## 安全

- 未读取、输出、落盘或提交真实 API Key。
- Chunk 正文接口从访问日志排除。
- 日志中未出现真实 PDF 正文短语。

## Git

- 分支：`master`
- HEAD：`230747e44131f825ae4c13f9f284b1b8602fd047`
- 暂存区为空。
- 未执行 commit、push 或 reset。
