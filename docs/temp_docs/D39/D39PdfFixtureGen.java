import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * D39-R01 P4 夹具 PDF 生成器（temp_docs 工具，不入库不进 git 源码目录）。
 * 读取 fixtures/docs/d39-fx-*.txt，按标点安全折行生成单页 A4 PDF 到 fixtures/pdfs/，
 * 并用 PDFTextStripper 回读自校验：提取文本含精确码 D2P-771（词面完整）且字符数接近原文。
 */
public class D39PdfFixtureGen {

    static final float MAX_WIDTH = 480f; // A4 - 2*40pt 左边距，按 11pt 字体
    static final char[] BREAKERS = {'，', '。', '；', '：', '、', ',', ';', ':', '\n'};

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();
        for (File txt : dir.listFiles((d, n) -> n.startsWith("d39-fx-") && n.endsWith(".txt"))) {
            String text = Files.readString(txt.toPath(), StandardCharsets.UTF_8)
                    .replace("\r", "").replace("\n", " ").replace("﻿", "").strip();
            File pdf = new File(outDir, txt.getName().replace(".txt", ".pdf"));
            List<String> lines;
            try (PDDocument doc = new PDDocument()) {
                PDFont font = PDType0Font.load(doc, new File("C:\\Windows\\Fonts\\simhei.ttf"));
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                lines = wrap(text, font);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11f);
                    cs.newLineAtOffset(40f, 780f);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLineAtOffset(0f, -16f);
                    }
                    cs.endText();
                }
                doc.save(pdf);
            }
            // 回读自校验
            String extracted;
            try (PDDocument d2 = Loader.loadPDF(pdf)) {
                extracted = new PDFTextStripper().getText(d2).replace("\r", "").replace("\n", " ").strip();
            }
            int src = text.length();
            // 比较：去掉全部空白后长度
            long srcNoWs = text.chars().filter(c -> !Character.isWhitespace(c)).count();
            long extNoWs = extracted.chars().filter(c -> !Character.isWhitespace(c)).count();
            boolean markerOk = true;
            if (txt.getName().contains("d2p771")) {
                markerOk = extracted.contains("D2P-771");
            }
            System.out.printf("%s lines=%d srcChars=%d extractedNonWs=%d/srcNonWs=%d markerFull=%s%n",
                    pdf.getName(), lines.size(), src, extNoWs, srcNoWs, markerOk);
            if (Math.abs(extNoWs - srcNoWs) > 2 || !markerOk) {
                throw new IllegalStateException("SELF-CHECK FAILED for " + txt.getName());
            }
        }
        System.out.println("ALL PDF FIXTURES GENERATED & SELF-CHECKED OK");
    }

    /** 标点优先折行：只在 BREAKERS 后断行；单段超宽则强断（对含 '-' 词元会先尝试回退到词元起点）。 */
    static List<String> wrap(String text, PDFont font) throws Exception {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int n = text.length();
        while (start < n) {
            int i = start;
            int bestBreak = -1;
            float w = 0f;
            while (i < n) {
                String ch = text.substring(i, i + 1);
                float cw = font.getStringWidth(ch) / 1000f * 11f;
                if (w + cw > MAX_WIDTH && (bestBreak > start || !isTokenChar(text.charAt(i)))) {
                    break;
                }
                w += cw;
                if (indexOf(BREAKERS, text.charAt(i)) >= 0) bestBreak = i + 1;
                i++;
            }
            int end = (i >= n) ? n : (bestBreak > start ? bestBreak : i);
            lines.add(text.substring(start, end).strip());
            start = end;
        }
        lines.removeIf(String::isEmpty);
        return lines;
    }

    static boolean isTokenChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
    }

    static int indexOf(char[] arr, char c) {
        for (int k = 0; k < arr.length; k++) if (arr[k] == c) return k;
        return -1;
    }
}
