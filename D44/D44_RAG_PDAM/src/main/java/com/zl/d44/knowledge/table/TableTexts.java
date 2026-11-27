package com.zl.d44.knowledge.table;

import java.text.Normalizer;

/**
 * 双轨规范化口径（KP4 textNormalization 冻结）：NFC → U+3000→空格 → 连续空白折叠单空格 → trim。
 * 对页文本（PDFTextStripper 默认提取顺序）与表格行文本同口径，续表表头匹配同口径。
 */
public final class TableTexts {

    private TableTexts() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
        return nfc.replace('\u3000', ' ').replaceAll("\\s+", " ").strip();
    }

    /** 单元格原文清洗（抽取轨内使用）：仅首尾裁剪与空白折叠，不破坏 "12.5 mm" 型单位间隔。 */
    public static String cleanCell(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.replace('\u3000', ' ').replaceAll("\\s+", " ").strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
