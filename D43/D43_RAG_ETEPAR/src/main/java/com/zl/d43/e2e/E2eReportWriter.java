package com.zl.d43.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * D43 P4：证据落盘工具（§6.7 目录口径；“定义与结果分离”——定义取 E2eScenario/冻结件原文，
 * 结果只写实测观测值）。写入失败必须显式抛 E2E_REPORT_WRITE_FAILED 语义异常，禁静默。
 */
public final class E2eReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path evidenceRoot;

    public E2eReportWriter(Path evidenceRoot) {
        this.evidenceRoot = evidenceRoot;
    }

    /** JSON 落盘（自动建目录；UTF-8 无 BOM）。 */
    public void writeJson(String relativePath, Object payload) throws IOException {
        Path target = evidenceRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    /** Markdown/文本落盘（自动建目录；UTF-8 无 BOM）。 */
    public void writeText(String relativePath, String content) throws IOException {
        Path target = evidenceRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    public Path evidenceRoot() {
        return evidenceRoot;
    }
}