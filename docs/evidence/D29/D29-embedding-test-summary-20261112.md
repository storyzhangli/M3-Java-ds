# D29 Embedding 测试汇总

## 结果

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 基线全量 | `mvn -f .\pom.xml test` | 235 项，0 失败，0 错误，1 跳过 | `logs/D29/D29-baseline-test.log` |
| 主代码编译 | `mvn -f .\pom.xml -DskipTests compile` | 成功 | `logs/D29/D29-compile-01.log` |
| Embedding 专项与集成 | 5 个新增测试类 + 文档集成 | 33/33 通过 | `logs/D29/D29-embedding-tests-05.log` |
| 最终全量 | `mvn -f .\pom.xml test` | **255 项，0 失败，0 错误，1 跳过** | `logs/D29/D29-full-regression-02.log` |
| 打包 | `mvn -f .\pom.xml -DskipTests package` | 成功 | `logs/D29/D29-package-02.log` |

Surefire：74 个测试类，255 项测试，0 失败，0 错误，1 跳过。

唯一跳过项是既有真实 DeepSeek 烟雾测试；它与 Embedding 无关。本轮没有调用真实 OpenAI Embedding API。

## 新增测试

- 本地模型中文/英文/数字向量生成。
- 相同输入维度与数值稳定。
- 相关技术文本相似度高于无关文本。
- null、空串、纯空白拒绝。
- 超长输入拒绝。
- 空向量、维度错误、NaN、Infinity、零向量拒绝。
- 临时限流/超时/服务异常有限重试。
- 重试成功、重试耗尽。
- 永久认证失败不重试。
- 同维度不同模型隔离。
- 不同维度隔离。
- indexVersion 和距离度量不兼容。
- Chunk 元数据保留。
- 模型信息 REST。
- 真实 PDF → Chunk → 384 维 Embedding 集成。
