# D27 最终审计

## 代码

- PDFBox 3.0.7 页面 API 编译并真实运行。
- PDF 原始文件与页面提取文本分表。
- 页面唯一键为 `(document_id, page_number)`。
- 页面查询按物理页码升序。
- 文档完成与页面插入在同一事务。
- 解析失败不写成功页面。

## 测试

- 最终全量：217 项，0 失败，0 错误，1 跳过。
- 新增 parser 覆盖 7 项、integration 覆盖 12 项、真实解析证据 1 项。
- 基线 208 项全部继续通过。
- 跳过项仅为既有真实 DeepSeek 烟雾测试。

## 真实样本

- 11 页 Canon PDF。
- 1,509,800 字节。
- SHA-256 `566a1f050d7cf512c750d1c0f5e699438d839dca7b0cf1266ef282c8dfbee83a`。
- PDFBox 3.0.7 提取 9,289 字符。
- 第 1/2/10/11 页已目视抽查。

## 依赖

- 未升级 Spring Boot、Spring AI、Tika。
- 未新增重复 PDF 引擎。
- Word 复用 Tika/POI 传递依赖。

## 安全

- 未使用真实 DeepSeek Key。
- 工作区、HEAD 严格 Key 扫描为 0。
- 暂存区为空。

## Git

- 当前分支：`master`。
- 当前 HEAD：`add06e5d7fd2fd1986a03f38de37d8fd00ab5bdf`。
- 该提交为用户对 D27 任务书的独立提交。
- 本轮未 commit、push、reset。

## 产物

- JAR 字节数：114,710,127。
- JAR SHA-256：`12C89750A55C10BE62280B6BC478119267A09914513939BA92CB0C010E73D3BE`。
