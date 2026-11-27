package com.zl.d44.knowledge.table.model;

/**
 * 类型化单元格（KP4 冻结 cellSchema：{"h","v","t","u"}）。
 *
 * @param header 所属列名（表头快照）
 * @param value  原文本；缺失单元格为 null（null 占位不串列，D10）
 * @param type   number / string / null
 * @param unit   单位（number 时按 KP4 unitRegex 抽取；缺失单位显式 null）
 */
public record TableCell(String header, String value, String type, String unit) {

    public static TableCell missing(String header) {
        return new TableCell(header, null, "null", null);
    }

    public boolean isMissing() {
        return value == null;
    }

    /** 行文本（双轨关联匹配用）：缺失单元格以空段参与，不伪造文本。 */
    public String textOrEmpty() {
        return value == null ? "" : value;
    }
}
