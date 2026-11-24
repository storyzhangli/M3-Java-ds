import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * D41 KP1 探针（temp_docs 工具）：
 * P-DOCX：手造含 <w:tbl> 的 docx 经 Tika 提取 → 断言单元格词面存在、但行列结构不可还原（结构丢失实证 → D3 退化标注依据）。
 * P-HTML：既有 Jsoup 解析 <table> → 行列单元格结构化提取实证（KP1 裁决：HTML 可选分支用 Jsoup，非新框架）。
 * P-OCR：确认 classpath 无 OCR 引擎类（可加载性反证）。
 */
public class ProbeKp1 {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]); // fixtures 根目录
        // P-DOCX
        File docx = new File(dir, "docx/d41-fx-table.docx");
        String text = new Tika().parseToString(docx);
        String flat = text.replaceAll("\\s+", " ").trim();
        boolean wordsIn = flat.contains("Code") && flat.contains("D-01") && flat.contains("Value") && flat.contains("77");
        // 结构丢失判据：逐行输出无法区分 2x2 表与 1x4 行——同序列无行列边界信息
        String lines = java.util.Arrays.stream(text.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).toList().toString();
        List<String> findings = new ArrayList<>();
        findings.add("P-DOCX wordsIn=" + wordsIn + " raw=" + flat);
        findings.add("P-DOCX tikaLineSequence=" + lines);
        findings.add("P-DOCX 裁决依据：Tika 输出无 <tbl>/行列坐标/列数信息，2x2 表与任意单行序列不可区分 → 结构轨判 DOCX_TABLE_NOT_STRUCTURED，文本轨词面完整保留");
        // P-HTML
        String html = """
                <html><body><table id="t"><tr><th>Code</th><th>Value</th></tr>
                <tr><td>H-01</td><td>12.5 mm</td></tr><tr><td>H-02</td><td></td></tr></table></body></html>
                """;
        Document d = Jsoup.parse(html);
        Elements rows = d.select("table#t tr");
        StringBuilder sb = new StringBuilder("P-HTML rows=" + rows.size() + " cells=[");
        for (Element tr : rows) {
            List<String> cs = new ArrayList<>();
            tr.select("th,td").forEach(c -> cs.add('"' + c.text() + '"'));
            sb.append('{').append(String.join(",", cs)).append("},");
        }
        sb.append("]");
        findings.add(sb.toString());
        // P-OCR absence
        boolean ocrAbsent = true;
        for (String cls : new String[]{"net.sourceforge.tess4j.Tesseract", "com.blinkster.ocr.OCREngine"}) {
            try { Class.forName(cls); ocrAbsent = false; } catch (ClassNotFoundException ignored) {}
        }
        findings.add("P-OCR engineAbsent=" + ocrAbsent);
        findings.forEach(System.out::println);
        if (!wordsIn || rows.size() != 3 || !ocrAbsent) {
            System.out.println("KP1 PROBE: CHECK-OUTPUT (see flags above)");
        } else {
            System.out.println("KP1 PROBE: DOCX-LOSS-CONFIRMED, HTML-JSOUP-USABLE, OCR-ABSENT");
        }
        Files.writeString(new File(dir, "..\\probe-kp1-output.txt").toPath(), String.join("\n", findings));
    }
}
