package com.zl.d43.knowledge.table.model;

import java.util.List;

/**
 * 抽取数据行（表头行不入数据行；全 null 行不产生记录——由构造方保证）。
 *
 * @param rowIndex         数据行序（0 起，区域相对）
 * @param cells            按区域列序的类型化单元格（含 null 占位）
 * @param rowText          行文本（空格连接，双轨包含匹配输入）
 * @param sourceLineNumber 来源行号（文本轨=文件行号；PDF 轨=页内聚类行序，1 起）；无则 null
 * @param flags            行级检出标注（MERGED_CELL / COLUMN_MISALIGNMENT）
 */
public record TableRowData(int rowIndex,
                           List<TableCell> cells,
                           String rowText,
                           Integer sourceLineNumber,
                           List<String> flags) {

    public TableRowData {
        cells = List.copyOf(cells);
        flags = List.copyOf(flags);
    }
}
