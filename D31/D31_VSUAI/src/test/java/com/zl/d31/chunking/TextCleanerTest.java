package com.zl.d31.chunking;

import com.zl.d31.knowledge.chunking.ChunkSourceDocument;
import com.zl.d31.knowledge.chunking.CleanedDocument;
import com.zl.d31.knowledge.chunking.DocumentPageText;
import com.zl.d31.knowledge.chunking.TextCleaner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner();

    @Test
    void cleaningShouldNormalizeNoiseAndPreserveDocumentStructure() {
        String original = "\uFEFF1. INTRODUCTION\r\n\r\n"
                + "This   manual\tcovers E05.\r\n"
                + "- Keep  2.5-inch LCD\r\n"
                + "- Add\u00A0ink\n\n\n\n"
                + "English 🔧 text.";
        ChunkSourceDocument source = new ChunkSourceDocument(
                "doc-1",
                "设备手册",
                "v1",
                List.of(new DocumentPageText(3, original)));

        CleanedDocument cleaned = cleaner.clean(source);

        assertThat(cleaned.normalizedText())
                .doesNotContain("\r", "\t", "\u00A0", "\uFEFF", "\n\n\n")
                .contains("1. INTRODUCTION")
                .contains("This manual covers E05.")
                .contains("- Keep 2.5-inch LCD")
                .contains("- Add ink")
                .contains("English 🔧 text.");
        assertThat(cleaned.paragraphs()).hasSize(5);
        assertThat(cleaned.paragraphs().get(0).heading()).isTrue();
        assertThat(cleaned.paragraphs().get(0).pageNumber()).isEqualTo(3);
        assertThat(cleaned.paragraphs().get(0).paragraphIndex()).isEqualTo(1);
        assertThat(cleaned.pages()).singleElement().satisfies(page -> {
            assertThat(page.originalText()).isEqualTo(original);
            assertThat(page.pageNumber()).isEqualTo(3);
            assertThat(page.paragraphCount()).isEqualTo(5);
        });
    }

    @Test
    void whitespaceOnlyAndEmptyPagesShouldProduceNoParagraphs() {
        CleanedDocument cleaned = cleaner.clean(new ChunkSourceDocument(
                "doc-empty",
                "empty",
                "1",
                List.of(
                        new DocumentPageText(1, " \r\n\t\u00A0 "),
                        new DocumentPageText(2, ""))));

        assertThat(cleaned.normalizedText()).isEmpty();
        assertThat(cleaned.paragraphs()).isEmpty();
        assertThat(cleaned.pages()).hasSize(2);
        assertThat(cleaned.pages()).allSatisfy(page ->
                assertThat(page.cleanedCharacterCount()).isZero());
    }

    @Test
    void chineseEnglishNumbersAndTechnicalPunctuationShouldRemainIntact() {
        String original = "设备型号：PIXMA MP500。\r\n故障码 E05；电压 220 V。";

        String cleaned = cleaner.cleanText(original);

        assertThat(cleaned).isEqualTo("设备型号：PIXMA MP500。\n故障码 E05；电压 220 V。");
    }

    @Test
    void meaningfulZeroWidthJoinerInEmojiMustBePreserved() {
        String familyEmoji = "👩\u200D👩\u200D👧";

        assertThat(cleaner.cleanText("设备 " + familyEmoji + " 正常"))
                .isEqualTo("设备 " + familyEmoji + " 正常");
    }
}
