package com.zl.d26.knowledge.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(parsed.text()).contains("设备检查", "先确认冷却液液位");
    }

    @Test
    void validPdfShouldParseWithTikaAndPdfBox() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Equipment maintenance manual");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", pdf);

        KnowledgeDocumentParser.ParsedFile parsed = parser.parse(parser.inspect(file));

        assertThat(parsed.contentType()).isEqualTo("application/pdf");
        assertThat(parsed.text()).contains("Equipment maintenance manual");
    }
}
