package com.zl.d43.knowledge.table.dto;

import com.zl.d43.knowledge.table.model.TableCell;

import java.util.List;

/** 数据行视图。 */
public record TableRowView(long tableId,
                           int rowIndex,
                           List<TableCell> cells,
                           Integer sourceLineNumber,
                           String recordHash) {
}
