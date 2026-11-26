package com.zl.d43.knowledge.table;

import com.zl.d43.knowledge.table.model.TableCell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KP4 冻结口径单测：normalize/cleanCell 与 UnitTyping（number+unit 拆分）。
 */
class TableTextsUnitTypingTest {

    @Test
    void normalizeCollapsesFullWidthSpaceAndWhitespace() {
        // KP4 冻结口径：NFC + U+3000→空格 + 折叠；全角字母不转半角（NFC 不改变 Ａ）
        assertEquals("Ａ Ｂ", TableTexts.normalize("Ａ　Ｂ"));
        assertEquals("x y", TableTexts.normalize("  x    y  "));
        assertEquals("", TableTexts.normalize(null));
    }

    @Test
    void cleanCellTrimsInternalRun() {
        assertEquals("E-101", TableTexts.cleanCell("  E-101  "));
    }

    @Test
    void unitTypingSplitsNumberAndUnit() {
        TableCell number = UnitTyping.typeOf("Value", "12.5 mm");
        assertEquals("number", number.type());
        assertEquals("mm", number.unit());

        TableCell signed = UnitTyping.typeOf("Value", "-3.2");
        assertEquals("number", signed.type());
        assertNull(signed.unit());

        TableCell tolerance = UnitTyping.typeOf("Value", "±0.01");
        assertEquals("number", tolerance.type());

        TableCell volt = UnitTyping.typeOf("Value", "380V");
        assertEquals("number", volt.type());
        assertEquals("V", volt.unit());

        TableCell range = UnitTyping.typeOf("Value", "2..8");
        assertEquals("string", range.type());

        TableCell text = UnitTyping.typeOf("Code", "E-101");
        assertEquals("string", text.type());
    }

    /** D13 裁决（R01 S2 对账回归）：尺寸记法 x+数字判 string，不得误抽 unit。 */
    @Test
    void dimensionNotationStaysString() {
        TableCell size = UnitTyping.typeOf("Spec", "25x52x15");
        assertEquals("string", size.type());
        assertNull(size.unit());

        TableCell size2 = UnitTyping.typeOf("Spec", "52x65x8");
        assertEquals("string", size2.type());
        assertNull(size2.unit());

        TableCell rpm = UnitTyping.typeOf("Speed", "1500 rpm");
        assertEquals("number", rpm.type());
        assertEquals("rpm", rpm.unit());

        TableCell dn = UnitTyping.typeOf("Spec", "DN25");
        assertEquals("string", dn.type());
    }
}
