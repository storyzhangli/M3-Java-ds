# D26 运行与构建证据

## 产物

```text
Path: E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D26\D26_DIABP\target\D26_DIABP-1.0-SNAPSHOT.jar
Bytes: 114696142
SHA-256: C1F5784816323E4D5135FF70B093113A5B20A4CE504309F53339C9711D8D99DF
```

## 日志

- 基线全量：`logs/D26/D26-baseline-full-test.log`
- 上传初轮：`logs/D26/D26-document-import-tests-01.log`
- 上传修复后：`logs/D26/D26-document-import-tests-03.log`
- 导入集成：`logs/D26/D26-import-integration-final.log`
- Parser：`logs/D26/D26-parser-tests-01.log`
- 最终全量：`logs/D26/D26-full-regression-01.log`
- 打包：`logs/D26/D26-package-01.log`

## 数据库迁移

```text
D26/D26_DIABP/src/main/resources/db/schema-knowledge-document.sql
```

应用启动不会自动执行，需部署前在目标 MySQL 显式应用。
