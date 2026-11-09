# D27 敏感信息扫描

## 结果

| 范围/模式 | 命中 |
|---|---:|
| 工作区 DeepSeek 风格 Key `sk-...` | 0 |
| 工作区 DeepSeek API Key 字面赋值 | 0 |
| Git HEAD `sk-...` | 0 |
| 暂存区内容 | 0 字节 |

## 安全处理

- 未读取、输出或写入真实 DeepSeek API Key。
- 未发起真实 DeepSeek API 调用。
- 测试继续使用 `AI_MODE=mock` 和无效测试值覆盖。
- 页面提取正文接口已从通用访问日志排除。
- PDF 原始文件只在测试数据中处理，公开手册正文证据不含凭据。
- 日志中的聊天 Prompt/Completion 继续由既有精确字段脱敏。
