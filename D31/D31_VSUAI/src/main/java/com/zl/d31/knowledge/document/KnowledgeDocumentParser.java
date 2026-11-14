package com.zl.d31.knowledge.document;

import com.zl.d31.common.ResultCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Version;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 上传文件的安全检查与基础解析。
 */
@Component
public class KnowledgeDocumentParser {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");

    private static final Set<String> HTML_EXTENSIONS = Set.of("html", "htm");

    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    private static final Set<String> DOCX_EXTENSIONS = Set.of("docx");

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/markdown", "text/x-markdown", "text/x-web-markdown");

    private static final Set<String> HTML_MIME_TYPES = Set.of(
            "text/html", "application/xhtml+xml");

    private static final Set<String> DOCX_MIME_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final KnowledgeImportProperties properties;

    public KnowledgeDocumentParser(KnowledgeImportProperties properties) {
        this.properties = properties;
    }

    /**
     * 进入 PROCESSING 前的最小检查：文件名、扩展名、非空、大小和字节读取。
     */
    public InspectedFile inspect(MultipartFile file) {
        if (file == null) {
            throw invalid("上传文件不能为空");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        if (!StringUtils.hasText(extension)
                || properties.getAllowedExtensions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .noneMatch(extension::equals)) {
            throw invalid("不支持的文件扩展名");
        }
        if (file.isEmpty() || file.getSize() <= 0) {
            throw invalid("上传文件不能为零字节");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw invalid("上传文件超过大小限制");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_FILE_INVALID, "文件读取失败", ex);
        }
        if (content.length == 0) {
            throw invalid("上传文件不能为零字节");
        }
        if (content.length > properties.getMaxFileSize().toBytes()) {
            throw invalid("上传文件超过大小限制");
        }
        return new InspectedFile(fileName, extension, content.length,
                sha256(content), content);
    }

    /**
     * 进入 PROCESSING 后执行内容识别、UTF-8 校验和基础文本解析。
     */
    public ParsedFile parse(InspectedFile file) {
        String detectedType;
        try {
            detectedType = normalizeMime(new Tika().detect(file.content(), file.fileName()));
        } catch (RuntimeException ex) {
            throw parseFailure("无法识别文件内容类型", ex);
        }

        String text = "";
        List<ExtractedPage> pages = List.of();
        String parserName;
        String parserVersion;
        if (TEXT_EXTENSIONS.contains(file.extension())) {
            requireMime(file.extension(), detectedType, TEXT_MIME_TYPES);
            text = decodeUtf8(file.content());
            parserName = "UTF-8 text";
            parserVersion = "built-in";
        } else if (HTML_EXTENSIONS.contains(file.extension())) {
            requireMime(file.extension(), detectedType, HTML_MIME_TYPES);
            decodeUtf8(file.content());
            text = readWithTika(file);
            parserName = "Apache Tika";
            parserVersion = tikaVersion();
        } else if (PDF_EXTENSIONS.contains(file.extension())) {
            requireMime(file.extension(), detectedType, Set.of("application/pdf"));
            return parsePdf(file);
        } else if (DOCX_EXTENSIONS.contains(file.extension())) {
            requireMime(file.extension(), detectedType, DOCX_MIME_TYPES);
            text = readWithTika(file);
            parserName = "Apache Tika";
            parserVersion = tikaVersion();
        } else {
            throw invalid("不支持的文件扩展名");
        }

        String normalized = text == null ? "" : text.strip();
        if (!StringUtils.hasText(normalized)) {
            throw parseFailure("文件未解析出可用文本");
        }
        if (normalized.length() > properties.getMaxParsedChars()) {
            throw parseFailure("解析文本超过配置上限");
        }
        return new ParsedFile(detectedType, normalized, pages, parserName, parserVersion);
    }

    private ParsedFile parsePdf(InspectedFile file) {
        try (PDDocument document = Loader.loadPDF(file.content())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw parseFailure("PDF 不包含有效页面");
            }
            if (pageCount > properties.getMaxPdfPages()) {
                throw parseFailure("PDF 页数超过配置上限");
            }

            List<ExtractedPage> pages = new ArrayList<>(pageCount);
            int totalCharacters = 0;
            int nonBlankPages = 0;
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String extracted = stripper.getText(document);
                boolean blank = !StringUtils.hasText(extracted);
                String content = blank ? "" : extracted.strip();
                if (!blank) {
                    nonBlankPages++;
                }
                totalCharacters += content.length();
                if (totalCharacters > properties.getMaxParsedChars()) {
                    throw parseFailure("解析文本超过配置上限");
                }
                pages.add(new ExtractedPage(pageNumber, content, blank));
            }
            if (nonBlankPages == 0) {
                throw parseFailure("PDF 未解析出可用文本，扫描型 PDF 需要 OCR");
            }
            return new ParsedFile(
                    "application/pdf",
                    null,
                    List.copyOf(pages),
                    "Apache PDFBox",
                    Version.getVersion());
        } catch (KnowledgeDocumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw parseFailure("PDF 解析失败", ex);
        }
    }

    private String readWithTika(InspectedFile file) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.content(), file.fileName());
            List<Document> documents = new TikaDocumentReader(resource).get();
            return documents.stream()
                    .map(Document::getText)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException ex) {
            throw parseFailure("文件基础解析失败", ex);
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.indexOf('\0') >= 0) {
                throw invalid("文本文件包含非法 NUL 字符");
            }
            return text;
        } catch (CharacterCodingException ex) {
            throw new KnowledgeDocumentException(
                    ResultCode.DOCUMENT_FILE_INVALID,
                    "文本文件不是合法 UTF-8 编码",
                    ex);
        }
    }

    private String tikaVersion() {
        String version = Tika.class.getPackage().getImplementationVersion();
        return StringUtils.hasText(version) ? version : "unknown";
    }

    private void requireMime(String extension, String detectedType, Set<String> allowed) {
        if (!allowed.contains(detectedType)) {
            throw invalid("文件扩展名 " + extension + " 与实际内容类型不匹配");
        }
    }

    private String normalizeMime(String value) {
        if (!StringUtils.hasText(value)) {
            return "application/octet-stream";
        }
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(0, semicolon) : value)
                .trim().toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String value) {
        if (!StringUtils.hasText(value)) {
            throw invalid("文件名不能为空");
        }
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (!StringUtils.hasText(name) || name.length() > 255) {
            throw invalid("文件名不合法");
        }
        return name;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private KnowledgeDocumentException invalid(String message) {
        return new KnowledgeDocumentException(ResultCode.DOCUMENT_FILE_INVALID, message);
    }

    private KnowledgeDocumentException parseFailure(String message) {
        return new KnowledgeDocumentException(ResultCode.DOCUMENT_PARSE_FAILED, message);
    }

    private KnowledgeDocumentException parseFailure(String message, Throwable cause) {
        return new KnowledgeDocumentException(
                ResultCode.DOCUMENT_PARSE_FAILED, message, cause);
    }

    public record InspectedFile(
            String fileName,
            String extension,
            long size,
            String contentHash,
            byte[] content) {
    }

    public record ParsedFile(
            String contentType,
            String parsedText,
            List<ExtractedPage> pages,
            String parserName,
            String parserVersion) {

        public ParsedFile {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        public int characterCount() {
            return pages.isEmpty()
                    ? (parsedText == null ? 0 : parsedText.length())
                    : pages.stream().mapToInt(ExtractedPage::characterCount).sum();
        }
    }
}
