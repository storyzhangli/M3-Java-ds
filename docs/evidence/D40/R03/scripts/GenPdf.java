// D40 R03 演练 PDF 夹具生成器（pdfbox 3.0.7，单文件源码启动）。
// 用法：java -cp <pdfbox;fontbox;pdfbox-io;commons-logging> GenPdf.java <out.pdf> <pages> <charsPerPage>
// 文本为英文重复句（避免中文字体依赖），每页约 charsPerPage 字符，供页面级切分产生多 Chunk。
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public class GenPdf {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        int pages = Integer.parseInt(args[1]);
        int charsPerPage = Integer.parseInt(args[2]);
        String sentence = "Device maintenance procedure requires calibration before restart. ";
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(40, 750);
                    cs.setLeading(14);
                    int written = 0;
                    int line = 0;
                    while (written < charsPerPage) {
                        String text = "Page " + (p + 1) + " line " + (line++) + ": " + sentence;
                        cs.showText(text);
                        cs.newLine();
                        written += text.length();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
        }
        System.out.println("PDF_OK " + out + " pages=" + pages);
    }
}
