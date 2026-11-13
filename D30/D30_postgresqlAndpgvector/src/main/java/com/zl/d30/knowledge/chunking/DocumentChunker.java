package com.zl.d30.knowledge.chunking;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Configurable, traceable text chunker independent from persistence and AI models.
 */
@Component
public class DocumentChunker {

    private static final String LENGTH_UNIT = "UNICODE_CODE_POINTS";

    private static final Set<Integer> SENTENCE_ENDINGS = Set.of(
            (int) '.', (int) '!', (int) '?', (int) ';',
            0x3002, 0xFF01, 0xFF1F, 0xFF1B);

    private final TextCleaner cleaner;

    private final ChunkingProperties properties;

    public DocumentChunker(TextCleaner cleaner, ChunkingProperties properties) {
        this.cleaner = cleaner;
        this.properties = properties;
    }

    public CleanedDocument clean(ChunkSourceDocument source) {
        return cleaner.clean(source);
    }

    public ChunkingResult chunk(ChunkSourceDocument source) {
        return chunk(source, properties.getDefaultStrategy());
    }

    public ChunkingResult chunk(ChunkSourceDocument source, ChunkStrategy strategy) {
        properties.requireValid();
        return chunkCleaned(cleaner.clean(source), strategy == null
                ? properties.getDefaultStrategy()
                : strategy);
    }

    public ChunkingComparison compare(ChunkSourceDocument source) {
        properties.requireValid();
        CleanedDocument cleaned = cleaner.clean(source);
        return new ChunkingComparison(
                chunkCleaned(cleaned, ChunkStrategy.FIXED_LENGTH),
                chunkCleaned(cleaned, ChunkStrategy.STRUCTURE_AWARE));
    }

    private ChunkingResult chunkCleaned(CleanedDocument document, ChunkStrategy strategy) {
        String text = document.normalizedText();
        int total = codePointLength(text);
        List<Integer> safeParagraphEnds = safeParagraphEnds(document);
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;

        while (start < total) {
            if (properties.getOverlap() == 0) {
                while (start < total && Character.isWhitespace(codePointAt(text, start))) {
                    start++;
                }
                if (start >= total) {
                    break;
                }
            }
            Boundary boundary = selectBoundary(text, start, total, safeParagraphEnds, strategy);
            int end = boundary.offset();
            if (end <= start) {
                throw new IllegalStateException("Chunker made no progress");
            }
            String content = substringByCodePoints(text, start, end);
            if (content.isBlank()) {
                start = end;
                continue;
            }
            List<ChunkSource> sources = sourcesFor(document.paragraphs(), start, end);
            List<Integer> pageNumbers = sources.stream()
                    .map(ChunkSource::pageNumber)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            chunks.add(new DocumentChunk(
                    document.documentId(),
                    document.title(),
                    document.version(),
                    chunks.size() + 1,
                    strategy,
                    content,
                    codePointLength(content),
                    pageNumbers,
                    sources,
                    boundary.type()));

            if (end >= total) {
                break;
            }
            int next = end - properties.getOverlap();
            if (next <= start) {
                next = end;
            }
            start = next;
        }

        return new ChunkingResult(
                document.documentId(),
                document.title(),
                document.version(),
                strategy,
                LENGTH_UNIT,
                properties.getChunkSize(),
                properties.getOverlap(),
                properties.getMinChunkSize(),
                properties.getMaxChunkSize(),
                document.originalCharacterCount(),
                document.cleanedCharacterCount(),
                metrics(chunks),
                chunks);
    }

    private Boundary selectBoundary(String text, int start, int total,
                                    List<Integer> safeParagraphEnds,
                                    ChunkStrategy strategy) {
        int target = Math.min(total, start + properties.getChunkSize());
        int minimum = Math.min(target, start + properties.getMinChunkSize());
        int maximum = Math.min(total, start + properties.getMaxChunkSize());
        if (target >= total) {
            return new Boundary(total, ChunkBoundaryType.DOCUMENT_END);
        }

        if (strategy == ChunkStrategy.STRUCTURE_AWARE) {
            Integer paragraphEnd = lastBoundaryWithin(
                    safeParagraphEnds, minimum, maximum);
            if (paragraphEnd != null) {
                return new Boundary(
                        paragraphEnd,
                        paragraphEnd == total
                                ? ChunkBoundaryType.DOCUMENT_END
                                : ChunkBoundaryType.PARAGRAPH);
            }
            Integer sentenceEnd = findSentenceBoundary(text, minimum, maximum);
            if (sentenceEnd != null) {
                return new Boundary(sentenceEnd, ChunkBoundaryType.SENTENCE);
            }
            return maximum < total
                    ? new Boundary(maximum, ChunkBoundaryType.HARD_LIMIT)
                    : new Boundary(total, ChunkBoundaryType.DOCUMENT_END);
        }

        Integer sentenceEnd = findSentenceBoundary(text, minimum, target);
        if (sentenceEnd != null) {
            return new Boundary(sentenceEnd, ChunkBoundaryType.SENTENCE);
        }
        Integer whitespaceEnd = findWhitespaceBoundary(text, minimum, target);
        if (whitespaceEnd != null) {
            return new Boundary(whitespaceEnd, ChunkBoundaryType.WHITESPACE);
        }
        return new Boundary(target, ChunkBoundaryType.HARD_LIMIT);
    }

    private List<Integer> safeParagraphEnds(CleanedDocument document) {
        List<Integer> ends = new ArrayList<>();
        for (CleanedParagraph paragraph : document.paragraphs()) {
            if (!paragraph.heading() && paragraph.endCodePoint() > paragraph.startCodePoint()) {
                ends.add(paragraph.endCodePoint());
            }
        }
        return List.copyOf(ends);
    }

    private Integer lastBoundaryWithin(List<Integer> boundaries, int minimum, int maximum) {
        Integer selected = null;
        for (Integer boundary : boundaries) {
            if (boundary >= minimum && boundary <= maximum) {
                selected = boundary;
            } else if (boundary > maximum) {
                break;
            }
        }
        return selected;
    }

    private Integer findSentenceBoundary(String text, int minimum, int maximum) {
        for (int position = maximum; position >= minimum; position--) {
            int codePoint = codePointAt(text, position - 1);
            if (SENTENCE_ENDINGS.contains(codePoint)) {
                return position;
            }
        }
        return null;
    }

    private Integer findWhitespaceBoundary(String text, int minimum, int maximum) {
        for (int position = maximum; position >= minimum; position--) {
            if (Character.isWhitespace(codePointAt(text, position))) {
                return position;
            }
        }
        return null;
    }

    private List<ChunkSource> sourcesFor(List<CleanedParagraph> paragraphs,
                                         int start, int end) {
        Set<ChunkSource> sources = new LinkedHashSet<>();
        for (CleanedParagraph paragraph : paragraphs) {
            if (paragraph.startCodePoint() < end && paragraph.endCodePoint() > start) {
                sources.add(new ChunkSource(
                        paragraph.pageNumber(),
                        paragraph.paragraphIndex()));
            }
        }
        return List.copyOf(sources);
    }

    private ChunkingMetrics metrics(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return new ChunkingMetrics(
                    0, 0.0, 0, 0,
                    new EnumMap<>(ChunkBoundaryType.class),
                    Map.of());
        }
        int minimum = chunks.stream().mapToInt(DocumentChunk::characterCount).min().orElse(0);
        int maximum = chunks.stream().mapToInt(DocumentChunk::characterCount).max().orElse(0);
        double average = chunks.stream()
                .mapToInt(DocumentChunk::characterCount)
                .average()
                .orElse(0.0);

        Map<ChunkBoundaryType, Long> boundaries = new EnumMap<>(ChunkBoundaryType.class);
        for (ChunkBoundaryType type : ChunkBoundaryType.values()) {
            boundaries.put(type, 0L);
        }
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            boundaries.compute(chunk.endBoundary(), (key, value) -> value == null ? 1 : value + 1);
            distribution.merge(lengthBand(chunk.characterCount()), 1L, Long::sum);
        }
        return new ChunkingMetrics(
                chunks.size(),
                average,
                minimum,
                maximum,
                Map.copyOf(boundaries),
                Map.copyOf(distribution));
    }

    private String lengthBand(int length) {
        int lower = (length / 100) * 100;
        if (lower >= 600) {
            return "600+";
        }
        return lower + "-" + (lower + 99);
    }

    private int codePointAt(String text, int codePointIndex) {
        int charIndex = text.offsetByCodePoints(0, codePointIndex);
        return text.codePointAt(charIndex);
    }

    private String substringByCodePoints(String text, int start, int end) {
        int startChar = text.offsetByCodePoints(0, start);
        int endChar = text.offsetByCodePoints(startChar, end - start);
        return text.substring(startChar, endChar);
    }

    private int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    private record Boundary(int offset, ChunkBoundaryType type) {
    }
}
