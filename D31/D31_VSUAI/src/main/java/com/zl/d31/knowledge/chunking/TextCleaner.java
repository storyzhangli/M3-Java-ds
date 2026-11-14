package com.zl.d31.knowledge.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Conservative page-preserving text cleaner.
 *
 * <p>It removes presentation noise while retaining line, paragraph, heading,
 * list, punctuation, number, unit and technical-token boundaries.</p>
 */
@Component
public class TextCleaner {

    private static final Set<Integer> ZERO_WIDTH_OR_BOM = Set.of(
            0xFEFF, 0x200B, 0x2060);

    public CleanedDocument clean(ChunkSourceDocument source) {
        List<CleanedPage> pages = new ArrayList<>();
        List<CleanedParagraph> paragraphs = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        int originalCharacters = 0;

        for (DocumentPageText sourcePage : source.pages()) {
            String cleaned = cleanText(sourcePage.text());
            List<String> pageParagraphs = splitParagraphs(cleaned);
            pages.add(new CleanedPage(
                    sourcePage.pageNumber(),
                    sourcePage.text(),
                    cleaned,
                    codePointLength(sourcePage.text()),
                    codePointLength(cleaned),
                    pageParagraphs.size()));

            for (int index = 0; index < pageParagraphs.size(); index++) {
                String paragraphText = pageParagraphs.get(index);
                if (!normalized.isEmpty()) {
                    normalized.append("\n\n");
                }
                int start = normalized.codePointCount(0, normalized.length());
                normalized.append(paragraphText);
                int end = normalized.codePointCount(0, normalized.length());
                paragraphs.add(new CleanedParagraph(
                        sourcePage.pageNumber(),
                        index + 1,
                        start,
                        end,
                        paragraphText,
                        isHeading(paragraphText)));
            }
            originalCharacters += codePointLength(sourcePage.text());
        }

        String normalizedText = normalized.toString().strip();
        return new CleanedDocument(
                source.documentId(),
                source.title(),
                source.version(),
                normalizedText,
                originalCharacters,
                codePointLength(normalizedText),
                pages,
                paragraphs);
    }

    public String cleanText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String normalizedLines = normalizeCharacters(raw);
        String[] lines = normalizedLines.split("\n", -1);
        List<String> cleanedLines = new ArrayList<>(lines.length);
        boolean previousBlank = false;

        for (String rawLine : lines) {
            String line = collapseHorizontalWhitespace(rawLine.stripTrailing());
            if (line.isEmpty()) {
                if (!previousBlank && !cleanedLines.isEmpty()) {
                    cleanedLines.add("");
                }
                previousBlank = true;
            } else {
                cleanedLines.add(line);
                previousBlank = false;
            }
        }
        while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).isEmpty()) {
            cleanedLines.remove(cleanedLines.size() - 1);
        }
        return String.join("\n", cleanedLines).strip();
    }

    private String normalizeCharacters(String raw) {
        String lineNormalized = raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u2028', '\n')
                .replace('\u2029', '\n');
        StringBuilder output = new StringBuilder(lineNormalized.length());
        lineNormalized.codePoints().forEach(codePoint -> {
            if (ZERO_WIDTH_OR_BOM.contains(codePoint)) {
                return;
            }
            if (codePoint == '\t') {
                output.append("    ");
            } else if (codePoint == '\n') {
                output.append('\n');
            } else if (Character.getType(codePoint) == Character.SPACE_SEPARATOR) {
                output.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                output.appendCodePoint(codePoint);
            }
        });
        return output.toString();
    }

    private String collapseHorizontalWhitespace(String line) {
        StringBuilder output = new StringBuilder(line.length());
        boolean previousSpace = false;
        for (int offset = 0; offset < line.length(); ) {
            int codePoint = line.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == ' ') {
                if (!previousSpace) {
                    output.append(' ');
                }
                previousSpace = true;
            } else {
                output.appendCodePoint(codePoint);
                previousSpace = false;
            }
        }
        return output.toString().stripLeading();
    }

    private List<String> splitParagraphs(String cleaned) {
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : cleaned.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                flush(paragraphs, current);
                continue;
            }
            if (isHeading(line)) {
                flush(paragraphs, current);
                paragraphs.add(line);
                continue;
            }
            if (isListItem(line)) {
                flush(paragraphs, current);
                current.append(line);
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        flush(paragraphs, current);
        return List.copyOf(paragraphs);
    }

    private boolean isHeading(String paragraph) {
        String firstLine = paragraph.lines().findFirst().orElse("");
        String stripped = firstLine.strip();
        if (stripped.isEmpty() || codePointLength(stripped) > 120) {
            return false;
        }
        if (stripped.matches("^#{1,6}\\s+\\S.*")) {
            return true;
        }
        if (stripped.matches("^\\d+(?:\\.\\d+)+[.)]?\\s+\\p{L}.*")) {
            return true;
        }
        int letters = 0;
        boolean onlyUppercaseLetters = true;
        for (int offset = 0; offset < stripped.length(); ) {
            int cp = stripped.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (!Character.isUpperCase(cp)) {
                    onlyUppercaseLetters = false;
                }
            }
        }
        boolean shortNumbered = stripped.matches("^\\d+[.)]?\\s+\\p{L}.*")
                && !stripped.endsWith(":")
                && !stripped.endsWith(".")
                && codePointLength(stripped) <= 60;
        return shortNumbered || letters >= 4 && onlyUppercaseLetters;
    }

    private boolean isListItem(String line) {
        return line.matches("^[•·▪◦*-]\\s+.*")
                || line.matches("^\\d+[.)]\\s+.*");
    }

    private void flush(List<String> paragraphs, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            paragraphs.add(value);
        }
        current.setLength(0);
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
