package com.zl.d28.document;

import com.zl.d28.common.ResultCode;
import com.zl.d28.knowledge.document.KnowledgeDocumentException;
import com.zl.d28.knowledge.document.KnowledgeDocumentParser;
import com.zl.d28.knowledge.document.KnowledgeImportProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentParserTest {

    private final KnowledgeDocumentParser parser =
            new KnowledgeDocumentParser(new KnowledgeImportProperties());

    @Test
    void validHtmlShouldUseDetectedContentTypeAndExtractText() {
        byte[] html = "<html><body><h1>设备检查</h1><p>先确认冷却液液位。</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.html", "text/plain", html);

        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(file));

        assertThat(parsed.contentType()).isEqualTo("text/html");
        assertThat(parsed.parsedText()).contains("设备检查", "先确认冷却液液位");
        assertThat(parsed.pages()).isEmpty();
    }

    @Test
    void validPdfShouldPreservePhysicalPagesAndBlankPage() throws Exception {
        byte[] pdf = pdfWithPages("Equipment maintenance manual", "", "Page 3 troubleshooting");
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", pdf);

        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(file));

        assertThat(parsed.contentType()).isEqualTo("application/pdf");
        assertThat(parsed.parsedText()).isNull();
        assertThat(parsed.pages()).hasSize(3);
        assertThat(parsed.pages()).extracting(page -> page.pageNumber())
                .containsExactly(1, 2, 3);
        assertThat(parsed.pages().get(0).content()).contains("Equipment maintenance manual");
        assertThat(parsed.pages().get(1).blankPage()).isTrue();
        assertThat(parsed.pages().get(1).content()).isEmpty();
        assertThat(parsed.pages().get(2).content()).contains("Page 3 troubleshooting");
        assertThat(parsed.parserName()).isEqualTo("Apache PDFBox");
        assertThat(parsed.parserVersion()).isEqualTo("3.0.7");
    }

    @Test
    void mixedChineseAndEnglishPdfShouldExtractText() throws Exception {
        Path fontPath = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
        Assumptions.assumeTrue(Files.isRegularFile(fontPath), "simhei.ttf unavailable");
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType0Font font = PDType0Font.load(document, fontPath.toFile());
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(50, 700);
                content.showText("设备故障码 E05");
                content.newLineAtOffset(0, -20);
                content.showText("Replace Print Head 123");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(
                new MockMultipartFile("file", "mixed.pdf", "application/pdf", pdf)));

        assertThat(parsed.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.content()).contains("设备故障码", "E05", "Replace Print Head", "123");
        });
    }

    @Test
    void zeroPageAndAllBlankPdfShouldFailWithoutFakeText() throws Exception {
        byte[] zeroPage = saveDocument(new PDDocument());
        byte[] blankPages = pdfWithPages("", "");

        assertParseFailure(zeroPage, "empty.pdf");
        assertParseFailure(blankPages, "blank.pdf");
    }

    @Test
    void corruptedPdfShouldFailAsParseError() throws Exception {
        byte[] valid = pdfWithPages("A valid page");
        byte[] corrupted = Arrays.copyOf(valid, Math.max(32, valid.length / 2));

        assertParseFailure(corrupted, "corrupted.pdf");
    }

    @Test
    void imageOnlyScannedPdfShouldFailAndDeclareOcrBoundary() {
        assertThatThrownBy(() -> {
            byte[] pdf;
            try (PDDocument document = new PDDocument();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                BufferedImage image = new BufferedImage(300, 120, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.BLACK);
                graphics.drawString("SCANNED PAGE TEXT", 20, 60);
                graphics.dispose();
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(LosslessFactory.createFromImage(document, image), 50, 500);
                }
                document.save(output);
                pdf = output.toByteArray();
            }
            parser.parse(parser.inspect(
                    new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf)));
        }).isInstanceOfSatisfying(KnowledgeDocumentException.class, ex -> {
            assertThat(ex.getResultCode()).isEqualTo(ResultCode.DOCUMENT_PARSE_FAILED);
            assertThat(ex.getMessage()).contains("扫描型 PDF", "OCR");
        });
    }

    @Test
    void docxShouldUseOptionalTikaWholeDocumentExtraction() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun()
                    .setText("设备额定电压 220 V. English maintenance note.");
            document.write(output);
            docx = output.toByteArray();
        }

        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(
                new MockMultipartFile(
                        "file",
                        "manual.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx)));

        assertThat(parsed.parsedText()).contains("设备额定电压", "220 V", "English");
        assertThat(parsed.pages()).isEmpty();
        assertThat(parsed.parserName()).isEqualTo("Apache Tika");
    }

    private void assertParseFailure(byte[] pdf, String name) {
        assertThatThrownBy(() -> parser.parse(parser.inspect(
                new MockMultipartFile("file", name, "application/pdf", pdf))))
                .isInstanceOfSatisfying(KnowledgeDocumentException.class,
                        ex -> assertThat(ex.getResultCode())
                                .isEqualTo(ResultCode.DOCUMENT_PARSE_FAILED));
    }

    private byte[] pdfWithPages(String... texts) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(font, 12);
                        content.newLineAtOffset(50, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] saveDocument(PDDocument document) throws Exception {
        try (document; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.save(output);
            return output.toByteArray();
        }
    }
}
