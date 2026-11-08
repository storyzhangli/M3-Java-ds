# D26 上传安全与状态校验矩阵

| 校验/策略 | 执行位置 | 结果 |
|---|---|---|
| Multipart max-file-size | Spring Servlet/Tomcat | 真实 HTTP 超限测试返回 4101 |
| 业务 max-file-size | KnowledgeDocumentParser | 直接 Service 调用也不能绕过 |
| 零字节 | Parser inspect | 返回 4101，文档 FAILED |
| 文件读取异常 | Parser inspect | 返回 4101，文档 FAILED |
| 扩展名白名单 | Parser inspect | 未支持类型拒绝 |
| 实际 MIME 检测 | Tika | 不信任前端 Content-Type/仅扩展名 |
| 扩展名/MIME 匹配 | Parser parse | 伪装文件拒绝 |
| UTF-8 严格解码 | Parser parse | REPORT 模式拒绝非法字节 |
| NUL 字符 | Parser parse | 文本拒绝 |
| HTML/PDF 基础解析 | Tika/PDFBox | 成功解析文本，空文本失败 |
| 最大解析字符数 | Parser parse | 超限拒绝 |
| contentHash | SHA-256 原始字节 | 不依赖文件名 |
| content_hash 唯一 | MySQL unique | 并发最终一致性 |
| 状态 CAS | Update WHERE status IN(...) | 防止并发覆盖 |
| 失败收口 | Service catch | FAILED + 安全 errorCode/message |

## 状态

```text
登记 -> PENDING
导入抢占 -> PROCESSING
解析成功 -> COMPLETED
校验/读取/解析失败 -> FAILED
FAILED 可重试
PROCESSING 不允许并发重入
COMPLETED 相同 hash 幂等；不同 hash 冲突
```

容器层超限在 Controller 之前发生，当前文档保持 PENDING，允许客户端缩减文件后重试。
