import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * D41 KP3 受控构造夹具生成器（temp_docs 工具，来源标注「受控构造（PDFBox 绘制/Java2D 渲染/手造 OOXML）」）。
 * 产出（outDir 下）：
 *   pdfs/  d41-fx-faultcode.pdf 故障码表（真值表 T1）
 *          d41-fx-params.pdf   参数表含单位变体（T2，A1）
 *          d41-fx-anomaly-a2.pdf 小列距+跨列合并+缺列（T3，A2）
 *          d41-fx-anomaly-a3.pdf 跨页续表重复表头（T4，A3）
 *          d41-fx-anomaly-a4.pdf 缺失单元格/空首列/无空记录（T5，A4）
 *          d41-fx-anomaly-a5.pdf p1 正常表+ p2 纯图片表（T6+IMG，A5）
 *   texts/ d41-fx-pipe.md 管道表（含空格）/ d41-fx-pipe.txt 制表风格管道表（含重复表头）
 *   docx/  d41-fx-table.docx 手造 OOXML 表格（C18 结构丢失实证）
 *   truth/d41-fixture-truth.json 坐标真值（对账唯一依据：每格 text/x/y/row/col/unit/type/page）
 * 内置 KP1 探针：对每个 PDF 用自定义 Stripper 子类收集 TextPosition（x/yDirAdj/width/fontSize）自证坐标可用。
 * 运行：java -cp <pdfbox3.0.7 四jar> D41TableFixtureGen.java <outDir>
 */
public class D41TableFixtureGen {

    static final float LEFT = 40f;
    static final String FONT_PATH = "C:\\Windows\\Fonts\\simhei.ttf";
    static final List<String> truthJson = new ArrayList<>();
    static final List<Cell> cells = new ArrayList<>();

    record Cell(String file, String tableId, int page, int row, int col,
                String text, double x, double yBaseline, double fontSize,
                String expectType, String expectUnit, boolean isHeader) {}

    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        File pdfDir = mkdir(outDir, "pdfs");
        File txtDir = mkdir(outDir, "texts");
        File docxDir = mkdir(outDir, "docx");
        File truthDir = mkdir(outDir, "truth");

        genFaultCode(new File(pdfDir, "d41-fx-faultcode.pdf"));
        genParams(new File(pdfDir, "d41-fx-params.pdf"));
        genAnomalyA2(new File(pdfDir, "d41-fx-anomaly-a2.pdf"));
        genAnomalyA3(new File(pdfDir, "d41-fx-anomaly-a3.pdf"));
        genAnomalyA4(new File(pdfDir, "d41-fx-anomaly-a4.pdf"));
        genAnomalyA5(new File(pdfDir, "d41-fx-anomaly-a5.pdf"));

        writeTextFixtures(txtDir);
        writeDocxFixture(docxDir);

        // truth json
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"generatedAt\": \"2026-11-24\",\n  \"sourceNote\": \"受控构造（PDFBox 绘制 / Java2D 渲染图片 / 手造 OOXML docx）\",\n  \"cells\": [\n");
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            sb.append(String.format("    {\"file\":\"%s\",\"tableId\":\"%s\",\"page\":%d,\"row\":%d,\"col\":%d,\"text\":%s,\"x\":%.2f,\"yBaseline\":%.2f,\"fontSize\":%.1f,\"expectType\":\"%s\",\"expectUnit\":%s,\"isHeader\":%s}%s%n",
                    c.file, c.tableId, c.page, c.row, c.col, jsonStr(c.text), c.x, c.yBaseline, c.fontSize,
                    c.expectType, c.expectUnit == null ? "null" : jsonStr(c.expectUnit), c.isHeader,
                    i < cells.size() - 1 ? "," : ""));
        }
        sb.append("  ]\n}\n");
        Files.writeString(new File(truthDir, "d41-fixture-truth.json").toPath(), sb.toString());
        System.out.println("truth cells=" + cells.size());

        // KP1 探针：逐 PDF 收集 TextPosition 自证坐标可用
        for (File pdf : listOrEmpty(pdfDir)) {
            probe(pdf);
        }
        System.out.println("ALL FIXTURES GENERATED");
    }

    static File[] listOrEmpty(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".pdf"));
        return fs == null ? new File[0] : fs;
    }

    static File mkdir(File base, String name) {
        File f = new File(base, name);
        f.mkdirs();
        return f;
    }

    /** KP1 探针：自定义 Stripper 子类收集坐标，输出样本行数与 x/y/width 范围。 */
    static void probe(File pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<String> samples = new ArrayList<>();
            double[] minMax = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition tp) {
                    if (tp.getUnicode() != null && !tp.getUnicode().isBlank()) {
                        if (samples.size() < 3) {
                            samples.add(String.format("[t=%s x=%.1f y=%.1f w=%.1f fs=%.1f]",
                                    tp.getUnicode(), tp.getXDirAdj(), tp.getYDirAdj(), tp.getWidthDirAdj(), tp.getFontSizeInPt()));
                        }
                        minMax[0] = Math.min(minMax[0], tp.getXDirAdj());
                        minMax[1] = Math.max(minMax[1], tp.getXDirAdj());
                        minMax[2] = Math.min(minMax[2], tp.getYDirAdj());
                        minMax[3] = Math.max(minMax[3], tp.getYDirAdj());
                    }
                    super.processTextPosition(tp);
                }
            };
            stripper.setStartPage(1);
            stripper.setEndPage(d.getNumberOfPages());
            stripper.setLineSeparator("\n");
            String text = stripper.getText(d);
            long nonBlankPages = text.isBlank() ? 0 : 1;
            System.out.printf("PROBE %s pages=%d textBlank=%s xRange=[%.1f,%.1f] yRange=[%.1f,%.1f] samples=%s%n",
                    pdf.getName(), d.getNumberOfPages(), text.isBlank() ? "Y" : "N",
                    minMax[0], minMax[1], minMax[2], minMax[3], samples);
            if (samples.size() < 3 && nonBlankPages == 1) {
                throw new IllegalStateException("KP1 COORD PROBE FAILED: " + pdf.getName());
            }
        }
    }

    // ---------- 各夹具绘制 ----------

    static void headerRow(PDDocument doc, PDPageContentStream cs, PDFont font, float[] colX, float y, float fs, String... texts) throws Exception {
        for (int i = 0; i < texts.length; i++) {
            cs.beginText(); cs.setFont(font, fs); cs.newLineAtOffset(colX[i], y); cs.showText(texts[i]); cs.endText();
        }
    }

    static void record(String file, String tableId, int page, int row, float[] colX, int col, String text, double y, double fs, String type, String unit, boolean hdr) {
        cells.add(new Cell(file, tableId, page, row, col, text, colX[col], y, fs, type, unit, hdr));
    }

    static void genFaultCode(File pdf) throws Exception {
        String f = pdf.getName();
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float[] colX = {40f, 140f, 300f, 460f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                cs.showText("PUMP UNIT 故障代码表（受控构造）"); cs.endText();
                float y = 760f;
                String[] hdr = {"Code", "Fault", "Description", "Action"};
                headerRow(doc, cs, font, colX, y, 11f, hdr);
                for (int i = 0; i < 4; i++) record(f, "T1-faultcode", 1, 0, colX, i, hdr[i], y, 11, "string", null, true);
                String[][] rows = {
                        {"E-101", "Overpressure", "Pressure exceeds limit", "Check valve"},
                        {"E-102", "Low oil level", "Oil below MIN mark", "Refill ISO VG46"},
                        {"E-205", "Sensor drift", "PT3 reading unstable", "Recalibrate PT3"},
                        {"E-310", "Seal wear", "Leak over 5 drops/min", "Replace kit K-22"},
                        {"W-044", "Vibration warning", "RMS over 4.5 mm/s", "Balance rotor"},
                };
                for (int r = 0; r < rows.length; r++) {
                    float ry = y - (r + 1) * 16f;
                    for (int c = 0; c < 4; c++) {
                        cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[c], ry); cs.showText(rows[r][c]); cs.endText();
                        record(f, "T1-faultcode", 1, r + 1, colX, c, rows[r][c], ry, 10, "string", null, false);
                    }
                }
            }
            doc.save(pdf);
        }
    }

    static void genParams(File pdf) throws Exception {
        String f = pdf.getName();
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float[] colX = {40f, 190f, 300f, 400f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                cs.showText("备件参数表（受控构造，单位变体 A1）"); cs.endText();
                float y = 760f;
                String[] hdr = {"Parameter", "Value", "Unit", "Note"};
                headerRow(doc, cs, font, colX, y, 11f, hdr);
                for (int i = 0; i < 4; i++) record(f, "T2-params", 1, 0, colX, i, hdr[i], y, 11, "string", null, true);
                // row0: 值与单位同格 "12.5 mm" → number+mm（单位从值分离，Unit 列同时给 nominal 说明）
                Object[][] rows = {
                        {"Gap_Size", "12.5 mm", "mm", "number", "mm"},
                        {"Voltage", "380V", null, "number", "V"},
                        {"Bore", "4.2", null, "number", null},
                        {"Tolerance", "±0.01", "mm", "number", null},
                        {"Weight", "18.6 kg", null, "number", "kg"},
                        {"Flow_Rate", "2..8", "L/min", "string", null},
                };
                for (int r = 0; r < rows.length; r++) {
                    float ry = y - (r + 1) * 16f;
                    String p = (String) rows[r][0], v = (String) rows[r][1], u = (String) rows[r][2];
                    cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[0], ry); cs.showText(p); cs.endText();
                    cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[1], ry); cs.showText(v); cs.endText();
                    if (u != null) {
                        cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[2], ry); cs.showText(u); cs.endText();
                    }
                    String note = r == 0 ? "nominal" : "spec";
                    cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[3], ry); cs.showText(note); cs.endText();
                    record(f, "T2-params", 1, r + 1, colX, 0, p, ry, 10, "string", null, false);
                    record(f, "T2-params", 1, r + 1, colX, 1, v, ry, 10, (String) rows[r][3], (String) rows[r][4], false);
                    if (u != null) record(f, "T2-params", 1, r + 1, colX, 2, u, ry, 10, "string", null, false);
                    record(f, "T2-params", 1, r + 1, colX, 3, note, ry, 10, "string", null, false);
                }
            }
            doc.save(pdf);
        }
    }

    static void genAnomalyA2(File pdf) throws Exception {
        String f = pdf.getName();
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float[] colX = {40f, 90f, 140f, 190f}; // 列距 50pt < 60pt 阈值 → LOW_CONFIDENCE
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                cs.showText("A2 小列距+跨列合并变体（受控构造）"); cs.endText();
                float y = 760f;
                String[] hdr = {"Col_A", "Col_B", "Col_C", "Col_D"};
                headerRow(doc, cs, font, colX, y, 11f, hdr);
                for (int i = 0; i < 4; i++) record(f, "T3-a2", 1, 0, colX, i, hdr[i], y, 11, "string", null, true);
                // row1 正常
                String[] r1 = {"A1v", "B1v", "C1v", "D1v"};
                float ry = y - 16f;
                headerRow(doc, cs, font, colX, ry, 10f, r1);
                for (int i = 0; i < 4; i++) record(f, "T3-a2", 1, 1, colX, i, r1[i], ry, 10, "string", null, false);
                // row2 合并：B 位文本横跨 B/C 两列（宽度须越 140 锚且不越 190 锚）
                String merged = "Merged Cells";
                ry = y - 32f;
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[0], ry); cs.showText("A2v"); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[1], ry); cs.showText(merged); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[3], ry); cs.showText("D2v"); cs.endText();
                record(f, "T3-a2", 1, 2, colX, 0, "A2v", ry, 10, "string", null, false);
                record(f, "T3-a2", 1, 2, colX, 1, merged, ry, 10, "merged", null, false);
                record(f, "T3-a2", 1, 2, colX, 3, "D2v", ry, 10, "string", null, false);
                // row3 缺 C 列
                ry = y - 48f;
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[0], ry); cs.showText("A3v"); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[1], ry); cs.showText("B3v"); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[3], ry); cs.showText("D3v"); cs.endText();
                record(f, "T3-a2", 1, 3, colX, 0, "A3v", ry, 10, "string", null, false);
                record(f, "T3-a2", 1, 3, colX, 1, "B3v", ry, 10, "string", null, false);
                record(f, "T3-a2", 1, 3, colX, 3, "D3v", ry, 10, "string", null, false);
            }
            doc.save(pdf);
        }
    }

    static void genAnomalyA3(File pdf) throws Exception {
        String f = pdf.getName();
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            float[] colX = {40f, 190f, 340f};
            String[] hdr = {"Name", "Spec", "Batch"};
            String[][] rows = {{"Bearing 6205", "25x52x15", "B-2024-01"}, {"Seal K-22", "52x65x8", "B-2024-02"}, {"Valve V-30", "DN25", "B-2024-03"}};
            for (int p = 1; p <= 2; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                    cs.showText(p == 1 ? "备件清单表（受控构造，跨页续表 A3）" : "备件清单表（续）"); cs.endText();
                    float y = 760f;
                    headerRow(doc, cs, font, colX, y, 11f, hdr);
                    for (int i = 0; i < 3; i++) record(f, "T4-a3", p, 0, colX, i, hdr[i], y, 11, "string", null, true);
                    String[][] use = rows;
                    for (int r = 0; r < use.length; r++) {
                        float ry = y - (r + 1) * 16f;
                        headerRow(doc, cs, font, colX, ry, 10f, use[r]);
                        for (int i = 0; i < 3; i++) record(f, "T4-a3", p, r + 1, colX, i, use[r][i], ry, 10, "string", null, false);
                    }
                }
            }
            doc.save(pdf);
        }
    }

    static void genAnomalyA4(File pdf) throws Exception {
        String f = pdf.getName();
        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float[] colX = {40f, 190f, 340f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                cs.showText("A4 缺失单元格变体（受控构造）"); cs.endText();
                float y = 760f;
                String[] hdr = {"ID", "Val", "Flag"};
                headerRow(doc, cs, font, colX, y, 11f, hdr);
                for (int i = 0; i < 3; i++) record(f, "T5-a4", 1, 0, colX, i, hdr[i], y, 11, "string", null, true);
                float r1 = y - 16f;
                headerRow(doc, cs, font, colX, r1, 10f, "R1", "10", "OK");
                record(f, "T5-a4", 1, 1, colX, 0, "R1", r1, 10, "string", null, false);
                record(f, "T5-a4", 1, 1, colX, 1, "10", r1, 10, "number", null, false);
                record(f, "T5-a4", 1, 1, colX, 2, "OK", r1, 10, "string", null, false);
                float r2 = y - 32f;  // Val 缺 → null 占位
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[0], r2); cs.showText("R2"); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[2], r2); cs.showText("OK"); cs.endText();
                record(f, "T5-a4", 1, 2, colX, 0, "R2", r2, 10, "string", null, false);
                record(f, "T5-a4", 1, 2, colX, 2, "OK", r2, 10, "string", null, false);
                float r3 = y - 48f;  // 首列缺、后两列有 → 不串列
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[1], r3); cs.showText("7.5"); cs.endText();
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[2], r3); cs.showText("LOW"); cs.endText();
                record(f, "T5-a4", 1, 3, colX, 1, "7.5", r3, 10, "number", null, false);
                record(f, "T5-a4", 1, 3, colX, 2, "LOW", r3, 10, "string", null, false);
                // 第 4 行整行空（不绘制任何 TextPosition）→ 期望不产生空记录
                cs.beginText(); cs.setFont(font, 10f); cs.newLineAtOffset(colX[0], y - 80f); cs.showText("END NOTE"); cs.endText();
                record(f, "T5-a4", 1, -1, colX, 0, "END NOTE", y - 80f, 10, "string", null, false);
            }
            doc.save(pdf);
        }
    }

    static void genAnomalyA5(File pdf) throws Exception {
        String f = pdf.getName();
        BufferedImage img = new BufferedImage(460, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 460, 220);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("IMG_TABLE (Java2D rendered)", 20, 30);
        g.drawRect(20, 50, 400, 140);
        g.drawLine(20, 80, 420, 80); g.drawLine(20, 110, 420, 110); g.drawLine(20, 140, 420, 140); g.drawLine(20, 170, 420, 170);
        g.drawLine(150, 50, 150, 190); g.drawLine(290, 50, 290, 190);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("Code", 60, 72); g.drawString("Meaning", 180, 72); g.drawString("Level", 330, 72);
        g.drawString("I-9001", 40, 102); g.drawString("Comm loss", 180, 102); g.drawString("ERROR", 330, 102);
        g.drawString("I-9002", 40, 132); g.drawString("Timeout", 180, 132); g.drawString("WARN", 330, 132);
        g.drawString("I-9003", 40, 162); g.drawString("Parity", 180, 162); g.drawString("INFO", 330, 162);
        g.dispose();
        File png = new File(pdf.getParentFile(), "d41-fx-a5-image.png");
        ImageIO.write(img, "png", png);
        byte[] bytes = Files.readAllBytes(png.toPath());

        try (PDDocument doc = new PDDocument()) {
            PDFont font = PDType0Font.load(doc, new File(FONT_PATH));
            PDPage p1 = new PDPage(PDRectangle.A4);
            doc.addPage(p1);
            float[] colX = {40f, 190f, 340f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.beginText(); cs.setFont(font, 13f); cs.newLineAtOffset(LEFT, 800f);
                cs.showText("A5 图片表变体（受控构造，p1 文本 p2 图片）"); cs.endText();
                float y = 760f;
                headerRow(doc, cs, font, colX, y, 11f, "Page", "Content", "Kind");
                record(f, "T6-a5p1", 1, 0, colX, 0, "Page", y, 11, "string", null, true);
                record(f, "T6-a5p1", 1, 0, colX, 1, "Content", y, 11, "string", null, true);
                record(f, "T6-a5p1", 1, 0, colX, 2, "Kind", y, 11, "string", null, true);
                float ry = y - 16f;
                headerRow(doc, cs, font, colX, ry, 10f, "2", "rendered image table", "IMAGE");
                record(f, "T6-a5p1", 1, 1, colX, 0, "2", ry, 10, "number", null, false);
                record(f, "T6-a5p1", 1, 1, colX, 1, "rendered image table", ry, 10, "string", null, false);
                record(f, "T6-a5p1", 1, 1, colX, 2, "IMAGE", ry, 10, "string", null, false);
            }
            PDPage p2 = new PDPage(PDRectangle.A4);
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                PDImageXObject xo = PDImageXObject.createFromByteArray(doc, bytes, "a5");
                cs.drawImage(xo, 40f, 560f, 460f, 220f);
            }
            doc.save(pdf);
        }
    }

    static void writeTextFixtures(File txtDir) throws Exception {
        String md = """
                # 备件管道表（受控构造 d41-fx-pipe.md）

                | Code | Part | Qty |
                |---|---|---|
                | P-001 | Bearing 6205 | 4 |
                | P-002 |  | 2 |
                | P-003 | Seal K-22 |  |
                """;
        Files.writeString(new File(txtDir, "d41-fx-pipe.md").toPath(), md);
        String txt = """
                CODE | DESC | LIMIT
                E-101 | Overpressure | 12 bar
                E-102 | Low oil | 0.5 bar
                CODE | DESC | LIMIT
                E-310 | Drift | 2 %
                """;
        Files.writeString(new File(txtDir, "d41-fx-pipe.txt").toPath(), txt);
    }

    static void writeDocxFixture(File docxDir) throws Exception {
        // 手造最小 OOXML（zip+xml，不依赖 POI）：含 <w:tbl> 2x2 表格 → Tika 文本路径结构丢失实证
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                <w:p><w:r><w:t>DOCX 表格夹具（受控构造）</w:t></w:r></w:p>
                <w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>Code</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>D-01</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>77</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                </w:body>
                </w:document>
                """;
        File docx = new File(docxDir, "d41-fx-table.docx");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(docx.toPath()))) {
            put(z, "[Content_Types].xml", contentTypes);
            put(z, "_rels/.rels", rels);
            put(z, "word/document.xml", document);
        }
        System.out.println("docx written " + docx.length() + " bytes");
    }

    static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}


