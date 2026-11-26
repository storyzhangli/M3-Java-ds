package com.zl.d43.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSONL 调用台账（H9：仅结构字段；写失败显式可观测，不静默丢弃）。 */
public final class RerankCallLedger {

    private static final Logger LOG = LoggerFactory.getLogger(RerankCallLedger.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public RerankCallLedger(Path file) {
        this.file = file;
    }

    /** @return true=落盘成功；false=写失败（调用方须以 RERANK_LEDGER_WRITE_FAILED 显式暴露） */
    public synchronized boolean write(RerankLedgerEntry entry) {
        try {
            Path abs = file.toAbsolutePath();
            if (abs.getParent() != null) {
                Files.createDirectories(abs.getParent());
            }
            String line = mapper.writeValueAsString(entry.toOrderedMap()) + System.lineSeparator();
            Files.writeString(abs, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            LOG.warn("rerank ledger write failed (file={}, err={})", file, e.toString());
            return false;
        }
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}