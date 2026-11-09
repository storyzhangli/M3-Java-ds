# D27 源码与 Git 索引

- 生成时间：2026-11-09 18:26:24 +08:00
- Git HEAD：add06e5d7fd2fd1986a03f38de37d8fd00ab5bdf
- 分支：master
- 扫描范围：D27 POM、PDF 解析、页面持久化、文档接口、测试与证据。

## 工作区状态

```text
 M D27/D27_PDF_WORD/pom.xml
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentController.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentParser.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentService.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeImportProperties.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/dto/KnowledgeDocumentView.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/repository/KnowledgeDocumentRepository.java
 M D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/repository/MybatisKnowledgeDocumentRepository.java
 M D27/D27_PDF_WORD/src/main/resources/application.yml
 M D27/D27_PDF_WORD/src/main/resources/db/schema-knowledge-document.sql
 M D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/KnowledgeDocumentImportIntegrationTest.java
 M D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/KnowledgeDocumentParserTest.java
 M D27/pom.xml
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/ExtractedPage.java
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentPage.java
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/dto/KnowledgeDocumentExtractionView.java
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/dto/KnowledgeDocumentPageView.java
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/mapper/KnowledgeDocumentPageMapper.java
?? D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/po/KnowledgeDocumentPagePO.java
?? D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/RealPdfSourceEvidenceTest.java
?? docs/evidence/D27/D27-acceptance-matrix.md
?? docs/evidence/D27/D27-dependency-compatibility.md
?? docs/evidence/D27/D27-final-audit.md
?? docs/evidence/D27/D27-iteration-state.md
?? docs/evidence/D27/D27-jar-sha256.txt
?? docs/evidence/D27/D27-java-pdf-extraction.json
?? docs/evidence/D27/D27-project-scan.md
?? docs/evidence/D27/D27-real-pdf-pages.json
?? docs/evidence/D27/D27-real-pdf-quality-check.md
?? docs/evidence/D27/D27-sensitive-scan.md
?? docs/evidence/D27/D27-test-summary.md
?? "docs/proj_docs/D27-PDF\350\247\243\346\236\220\344\270\216\346\235\245\346\272\220\345\205\203\346\225\260\346\215\256\346\212\200\346\234\257\345\216\237\347\220\206\345\217\212\346\223\215\344\275\234\345\256\236\350\267\265\346\214\207\345\215\227.md"
?? "docs/proj_docs/D27-PDF\350\247\243\346\236\220\344\270\216\346\235\245\346\272\220\345\256\232\344\275\215\345\212\237\350\203\275\345\256\236\347\216\260\345\205\250\351\207\217\350\257\264\346\230\216.md"
```

## 关键文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `D27/pom.xml` | `99D0C34BD276163F98431EC5361BD1891BBB193372DED22AFF0C5385C847CADD` |
| `D27/D27_PDF_WORD/pom.xml` | `A13F83D2EF648CF250B05DB6B9DF221D2A23311E8FDE012EB94C420249EF9C40` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentParser.java` | `74CAB38E86CE9B65FEF99734097D9CF7237E4FD511E8812847E02315644E5DD8` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentService.java` | `8E694CEA612781B9B608F43D312B94D18C7B33B5F120F708A90C01AA93DDA32B` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentController.java` | `599161A019C0D95E4CBE9287981FF8296A7A80DD0DDABD2E1F5A37F063CA1579` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/KnowledgeDocumentPage.java` | `50D1ACF96A2815717F07B009674F00D25134C19C3B00745F4E3F28C4F6ED3EDC` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/ExtractedPage.java` | `1824B31A336453D5BAFF697CE7111E3BDEC665EEB0816B4110D9869082FB656E` |
| `D27/D27_PDF_WORD/src/main/java/com/zl/d27/knowledge/document/repository/MybatisKnowledgeDocumentRepository.java` | `D6FB44525BE4768676BC9FA8DD4B95C754356B08BE6CB292971E63E2B8D45BA1` |
| `D27/D27_PDF_WORD/src/main/resources/db/schema-knowledge-document.sql` | `980E2F50DEA77883457104934244BEEFC051EED648E0961402E0B29BAB92AD0B` |
| `D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/KnowledgeDocumentParserTest.java` | `62B50F19AE0210C518960515299E87233906438C3BC30989A68C1A9EA3824474` |
| `D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/KnowledgeDocumentImportIntegrationTest.java` | `A957749943C126451EA65B1925ED96EC4327A1CF8BFD0BF7ABF1B814F38B6272` |
| `D27/D27_PDF_WORD/src/test/java/com/zl/d27/document/RealPdfSourceEvidenceTest.java` | `82F3291F531A10D3724E4061C92179EB26BCC791DA380F871FD620F69DB55009` |
