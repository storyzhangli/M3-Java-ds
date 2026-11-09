# D27 依赖兼容性证据

## Maven 依赖树

```text
org.ai.m2.ds:D27_PDF_WORD:jar:1.0-SNAPSHOT
+- org.springframework.ai:spring-ai-pdf-document-reader:jar:1.1.8:compile
|  \- org.apache.pdfbox:pdfbox:jar:3.0.7:compile
|     +- org.apache.pdfbox:pdfbox-io:jar:3.0.7:compile
|     \- org.apache.pdfbox:fontbox:jar:3.0.7:compile
\- org.springframework.ai:spring-ai-tika-document-reader:jar:1.1.8:compile
   \- org.apache.tika:tika-parsers-standard-package:jar:3.3.1:compile
      +- org.apache.tika:tika-parser-microsoft-module:jar:3.3.1:compile
      |  +- org.apache.poi:poi:jar:5.5.1:compile
      |  +- org.apache.poi:poi-ooxml:jar:5.5.1:compile
      |  \- org.apache.poi:poi-ooxml-full:jar:5.5.1:compile
      \- org.apache.tika:tika-parser-pdf-module:jar:3.3.1:compile
```

完整输出：`logs/D27/D27-dependency-tree.log`。

## 选型依据

- PDFBox 3.0.7 是当前 Spring AI 1.1.8 PDF Reader 的实际传递版本。
- 现有测试已使用 PDFBox 3.x 的 `Loader`/标准 14 字体 API，依赖可解析且编译通过。
- D27 仅把直接调用的 PDFBox 声明为显式依赖，并在根 POM 固定已验证的 3.0.7。
- 不新增第二套 PDF 解析器，不升级 Spring Boot、Spring AI 或 Tika。
- Word 复用 Tika 已有的 Microsoft Office 解析模块，不新增 POI 生产依赖。

## 验证

- `mvn -DskipTests compile`：成功。
- `mvn -DskipTests test-compile`：成功。
- PDFBox `Version.getVersion()`：`3.0.7`。
- Java PDF 实样解析：11 页全部成功。
