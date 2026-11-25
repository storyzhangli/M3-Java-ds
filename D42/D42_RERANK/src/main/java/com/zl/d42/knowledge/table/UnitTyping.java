package com.zl.d42.knowledge.table;

import com.zl.d42.knowledge.table.model.TableCell;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单元格类型化 + 单位抽取（KP4 unitRegex 冻结口径，§3.1）。
 *
 * <p>规则：number 仅当数值段整体匹配 {@code [±+-]?\d+(\.\d+)?}；允许尾随单位（可含空格），
 * 单位与数值同记录不拆散（D10/A1）；范围表达式（2..8）、复合文本一律 type=string 原文保留；
 * 缺失单位显式 null。</p>
 *
 * <p>D13 裁决（R01 S2 对账实证）：单位段以 x/X/× 起头且后随数字的形态是<b>尺寸记法</b>
 * （轴承 "25x52x15"、密封件 "52x65x8"），非单位记法——整体判 string，unit=null。
 * 真实单位以 x 起头且后随数字的形态（如倍率 "x2"）在设备手册规格列不存在，
 * 规格列 "DN25" 本就不匹配数值起头规则，维持 string。</p>
 */
public final class UnitTyping {

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "^([±+-]?\\d+(?:\\.\\d+)?)(?:\\s*([A-Za-zµ°%/][A-Za-z0-9µ°%/·.^\\-]*))?$");

    /** 尺寸记法单位段：x/X/× 起头且后随数字（"x52x15"）。 */
    private static final Pattern DIMENSION_SUFFIX = Pattern.compile("^[xX×].*\\d");

    private UnitTyping() {
    }

    public static TableCell typeOf(String header, String raw) {
        if (raw == null || raw.isEmpty()) {
            return TableCell.missing(header);
        }
        Matcher matcher = NUMBER_UNIT.matcher(raw);
        if (matcher.matches()) {
            String unit = matcher.group(2);
            if (unit != null && DIMENSION_SUFFIX.matcher(unit).find()) {
                return new TableCell(header, raw, "string", null);
            }
            return new TableCell(header, raw, "number", unit);
        }
        return new TableCell(header, raw, "string", null);
    }

    /** 纯数值判定（type=number 必要条件补充：±/±0.01 允许，2..8 拒绝）。 */
    public static boolean isPlainNumber(String value) {
        return value != null && value.matches("[±+-]?\\d+(\\.\\d+)?");
    }
}
