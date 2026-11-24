package com.zl.d41.knowledge.table.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一张抽取表格（结构化记录轨的领域对象；持久化映射 d41_table_extract + d41_table_row）。
 *
 * @param pageNumber        物理页码（docx 等无页概念=0）
 * @param tableIndex        页内表序（0 起）
 * @param header            表头列名（快照，续表匹配依据）
 * @param rows              数据行（表头/分隔行/全 null 行不在内）
 * @param colCount          区域列数
 * @param detectFlags       表级检出标注（LOW_CONFIDENCE/CONTINUATION_HEADER_SKIPPED/…）
 * @param confidence        HIGH / LOW（LOW_CONFIDENCE 标志同步置位）
 * @param continuationKey   续表关联：前表 (pageNumber,tableIndex)，null=非续表
 * @param sourceLineStart   文本轨来源行起（PDF=null）
 * @param sourceLineEnd     文本轨来源行止（PDF=null）
 * @param degradeStatus     ACTIVE / DEGRADED（能力退化占位记录，row 为空）
 */
public record ExtractedTable(int pageNumber,
                             int tableIndex,
                             List<String> header,
                             List<TableRowData> rows,
                             int colCount,
                             Set<String> detectFlags,
                             String confidence,
                             int[] continuationKey,
                             Integer sourceLineStart,
                             Integer sourceLineEnd,
                             String degradeStatus) {

    public ExtractedTable {
        header = List.copyOf(header);
        rows = List.copyOf(rows);
        detectFlags = Set.copyOf(new LinkedHashSet<>(detectFlags));
    }

    public String headerSnapshotJsonArray() {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escape(header.get(i)));
        }
        return builder.append(']').toString();
    }

    public static String escape(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
